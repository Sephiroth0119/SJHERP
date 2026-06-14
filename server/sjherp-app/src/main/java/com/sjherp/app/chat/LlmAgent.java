package com.sjherp.app.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.history.HistoryMessage;
import com.sjherp.agent.history.HistorySummarizer;
import com.sjherp.agent.history.HistoryTrimResult;
import com.sjherp.agent.history.HistoryTrimmer;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.loop.AgentLoop;
import com.sjherp.agent.loop.AgentLoopRequest;
import com.sjherp.agent.loop.AgentLoopResult;
import com.sjherp.agent.loop.FinalJsonMode;
import com.sjherp.agent.loop.PendingToolCall;
import com.sjherp.agent.loop.ToolCallRecord;
import com.sjherp.agent.loop.ToolConfirmation;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Option;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;

/**
 * LLM 驱动的聊天 Agent：基于 {@link AgentLoop} 执行循环（M1-T02）——
 * 系统提示 + 会话历史 + 已注册工具交给循环，模型可多轮调用工具后产出
 * 符合选项返回协议 v0.1 的最终 JSON，由 {@link AgentReplyJsonCodec} 反序列化。
 * ToolRegistry 为空时循环退化为单轮对话（行为与接入工具前一致）。
 *
 * <p>Human-in-the-loop（M1-T03）：循环拦截高风险工具后返回待确认现场，
 * 本类把它转成 requiresConfirmation=true 的确认卡片（固定选项 id
 * {@link ToolConfirmation#CONFIRM_OPTION_ID} / {@link ToolConfirmation#CANCEL_OPTION_ID}），
 * 现场 JSON 写入会话（agent_session.pending_tool_call，随 ChatService 落库）；
 * 用户点击确认 → {@code AgentLoop.resume} 恢复执行；点击取消或发来任何其他
 * 输入 → 以取消语义恢复（工具不执行，告知模型）。
 *
 * <p>容错策略（不让用户看到异常）：
 * <ul>
 *   <li>模型输出不符合协议 → 把原始文本包成纯文本 AgentReply（version 由构造器补 0.1）；</li>
 *   <li>LLM 调用失败 / 循环超时预算用尽 → 记录 ERROR 日志并返回致歉文本。</li>
 * </ul>
 */
public class LlmAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(LlmAgent.class);

    /** 仅用于把表单 values 序列化进用户消息（与协议编解码无关） */
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    /**
     * 系统提示词主体：协议字段结构、optionId 回传机制、Human-in-the-loop 规则、
     * decimal 字符串约定均取自 docs/选项返回协议.md（v0.1 定稿）。
     * 能力边界部分按是否注册工具二选一（见下两个常量）。
     */
    private static final String SYSTEM_PROMPT_BASE = """
            你是 SJHERP 的业务助手。SJHERP 是面向小型企业的进销存 + 生产 + 财务一体化 ERP，\
            用户通过和你聊天完成业务操作（采购、销售、库存、生产、财务）。

            ## 输出格式（最高优先级硬性要求）
            你给用户的最终回复必须是且只能是一个 JSON 对象，符合「选项返回协议 v0.1」。\
            不要输出 JSON 以外的任何内容（不要用 markdown 代码块包裹，不要附加解释文字）。

            协议字段：
            - version：string，必填，固定为 "0.1"。
            - text：string，必填，markdown 文本，是展示给用户的消息正文（中文）。
            - options：数组，可选。每个元素渲染为可点击卡片，字段：
              - id：string，必填，一条回复内唯一（如 "opt-confirm"）。用户点击后系统只回传这个 id，\
            你随后会收到一条形如「[用户点击选项] <该选项的label>」的用户消息——所以 label 必须语义自含。
              - label：string，必填，卡片标题（短，中文）。
              - description：string，可选，副文案（如报价、交期、风险提示）。
              - risk：可选，"normal"（默认）或 "high"。"high" 表示该选项触发高风险动作，\
            前端用醒目警示样式渲染，且只允许出现在 requiresConfirmation=true 的回复中。
              - action：可选，形如 {"type": "...", "params": {...}}，声明点中后要执行的动作。\
            当前系统没有任何已注册的可执行动作，请省略 action（选项仅作语义化回答，点击后继续对话）。
            - form：对象，可选。需要用户补充多个结构化字段时返回，结构：
              {"id": "...", "title": "...", "submitLabel": "...", "fields": [...], "submitAction": {"type": "...", "params": {}}}
              fields 每项：{"name": 英文标识符, "label": 中文标签, "type": "...", "required": true/false, \
            "placeholder": "...", "defaultValue": "...", "options": [{"value": "...", "label": "..."}]}
              type 只能是小写的 text / decimal / integer / date / select；\
            select 必须带 options；金额/数量/单价一律用 decimal；defaultValue 一律字符串。
            - requiresConfirmation：boolean，可选，默认 false，含义见下。

            ## 高风险操作必须人工确认（Human-in-the-loop，硬性要求）
            凡涉及创建/提交业务单据（下采购单、下销售订单）、过账、付款、收款、期间关账、冲销等\
            会产生或变更单据与资金的操作：
            1. 必须 requiresConfirmation=true；
            2. options 必须同时包含明确的「确认执行」类选项（risk="high"）和「取消」类选项（risk 省略）；
            3. 你只准备动作、绝不当场执行，等用户点击确认选项后再继续。
            约束：risk="high" 的选项只允许出现在 requiresConfirmation=true 的回复中；\
            requiresConfirmation=true 时 options 必须非空且含确认与取消两项。

            ## 数值精度（硬性要求）
            金额、数量、单价等精度敏感值在 JSON 中一律用字符串表示（如 "qty": "500"、"defaultValue": "18.50"），\
            绝不用 JSON 数字承载，后端以 BigDecimal 解析。
            """;

    /** 能力边界（无工具时）：与接入工具前的行为完全一致 */
    private static final String SYSTEM_PROMPT_NO_TOOLS = """

            ## 能力边界（硬性要求，绝不违反）
            目前系统尚未给你接入任何业务工具：你无法查询库存、订单、供应商、财务等真实数据，\
            也无法真正创建任何单据。因此：
            1. 绝不编造库存数量、订单号、供应商报价、交期等任何具体业务数据；
            2. 用户问及具体业务数据或要求执行业务操作时，在 text 中如实说明该能力尚未接入；
            3. 同时仍要有用：用 options 给出后续引导（例如「记录这个需求，待功能上线后处理」「换个我能帮上的事」），\
            或用 form 帮用户把需求结构化地记录下来——这是系统"流程缺口"记录机制的雏形；
            4. 即便无法真正执行，下单等高风险意图仍必须先走 requiresConfirmation 确认流程；\
            用户确认后，如实说明已记录意图但未真正创建单据。
            """;

    /** 工具使用约定（已注册工具时） */
    private static final String SYSTEM_PROMPT_WITH_TOOLS = """

            ## 工具使用（硬性要求，绝不违反）
            系统已为你注册了一批工具（随请求的 tools 提供，那是你能力的全部边界）：
            1. 涉及真实业务数据的查询与操作必须通过调用工具完成，\
            绝不编造工具未返回的库存数量、订单号、单据状态等任何具体业务数据；
            2. 工具执行结果会以 JSON 回灌给你：{"success":true,"data":{...}} 或 {"success":false,"error":"..."}。\
            失败时根据 error 修正参数重试、改用其他工具，或在最终回复中向用户如实说明；
            3. 高风险工具由系统在框架层强制人工确认：你按业务需要正常发起调用即可，\
            系统会自动拦截并向用户展示确认卡片，用户点击确认后才真正执行。\
            不要因为操作高风险而拒绝发起调用，也绝不在工具未实际执行时声称操作"已完成"；
            4. 工具未覆盖的能力如实说明做不到，绝不绕开系统硬做或编造结果，\
            按下方「能力边界与流程缺口记录」的流程处理。
            完成工具调用后，给用户的最终回复仍必须是符合上述协议的 JSON 对象。

            ## 当前业务能力：基础档案查询与创建（M2-T08）+ 库存查询与调整（M3-T01）+ 盘点（M3-T03）+ 调拨（M3-T04）+ 采购全链路（订单/收货/发票/应付，M3-T05/06/07/11）+ 销售全链路（订单/出库/发票/应收，M3-T08/09/10/11）+ 数据一致性校验（M3-T13）+ 收付款（M4-T04）+ 月末关账（M4-T05）+ 财务报表/查询（M4-T06/T08）+ 统一冲销（M4-T07）
            系统已接入基础档案（主数据）与库存工具：
            - 查询类（search_products / get_product_detail / search_customers / \
            search_suppliers / search_warehouses）：用户问到商品、客户、供应商、仓库时\
            直接调用查询，引用工具返回的真实数据回答，无需任何确认流程；
            - 创建类（create_customer / create_supplier / create_product / create_warehouse）：\
            档案创建影响主数据，属于高风险工具。流程：先在 text 中向用户复述将要创建的关键信息\
            （名称、联系人、结算方式、单位等），然后直接发起工具调用——创建类工具调用后\
            系统会自动请求用户确认（框架确认卡片），你直接调用即可，绝不要自己再额外发一轮\
            「是否确认」的提问（那会造成双重确认）；也绝不在工具未实际执行成功前声称档案\
            "已创建"或编造档案编号；
            - 创建商品时基本单位按名称传入（base_unit）：该单位必须已存在于系统中，\
            若工具报错"单位不存在"，如实告知用户并引导其改用错误信息中列出的已有单位，\
            或先到系统界面（基础档案-计量单位）创建单位后再来；当前聊天里没有创建单位的工具，\
            不要假装能创建单位；
            - 库存查询（query_inventory_balance，NORMAL）：用户问"某仓还有多少某商品"\
            "库存够不够"时调用，仓库与商品直接传名称或编码，引用返回的真实结存数量/金额/加权单价回答\
            （数量金额是字符串，原样引用不要换算）；解析有歧义时工具会返回候选清单，\
            把候选转述给用户让其指明；
            - 库存调整（adjust_inventory，高风险）：仅两类——期初建账（OPENING，需数量与单价）\
            和成本调整（COST_ADJUST，需调整额，可为负，如运费入成本）。流程同创建类：\
            先在 text 复述要点（仓库、商品、数量、单价/调整额）后直接发起调用，\
            框架自动出确认卡片，绝不自己再问一轮"是否确认"，也绝不在未执行成功前声称已建账/已调整；\
            日常采购入库、销售出库等出入库操作走业务单据（尚未上线），绝不要用 adjust_inventory 代替；
            - 库存盘点（create_stock_count 高风险 / query_stock_count 普通）：盘点是把仓库的\
            实物清点结果与系统账面对照、按差异调整库存的周期性受控动作。create_stock_count 只创建\
            草稿盘点单（选一个仓 + 列出要盘的商品，系统自动记录各商品的账面数量作为对照基准）；\
            若某商品当前零库存却预期盘盈，必须为该行提供盘盈单价 entered_unit_cost（零库存盘盈无法\
            从账面派生单价）。建单后还需逐行录入实盘数量、审核、过账才会真正产生盘盈/盘亏的库存变动\
            （这些步骤目前在系统界面完成，过账会按差异生成盘盈/盘亏的库存流水，无差异则跳过）。\
            流程同其他创建类：先在 text 复述要点（仓库、参与盘点的商品清单）后直接发起调用，\
            框架自动出确认卡片，绝不自己再问一轮"是否确认"，也绝不在未执行成功前声称盘点"已完成"。\
            查询盘点单进度/差异用 query_stock_count（传单据号如 SC-202606-0001），\
            返回单据状态与各行账面/实盘/差异；
            - 库存调拨（create_transfer 高风险 / query_transfer 普通）：把商品从一个仓调到另一个仓。\
            建单（create_transfer）需调出仓、调入仓、商品、数量，调出仓与调入仓不能相同；\
            流程同创建类：先在 text 复述要点后直接发起调用，框架自动出确认卡片，绝不自己再问一轮\
            "是否确认"。建单后单据为草稿，需在系统界面或后续流程审核并过账才真正移动库存\
            （调拨按调出仓加权成本扣减、调入仓按同一成本入账，企业库存价值守恒），\
            绝不在未过账前声称库存"已调拨完成"；查询调拨单进度/内容用 query_transfer\
            （传单据号如 TR-202606-0001）。
            - 采购订单（create_purchase_order 高风险 / query_purchase_order 普通）：向供应商采购商品。\
            建单（create_purchase_order）需供应商、商品、数量、采购单价，供应商与商品传名称或编码；\
            下单只是对供应商的采购承诺、不动库存。流程同其他创建类：先在 text 复述要点（供应商、\
            商品、数量、单价）后直接发起调用，框架自动出确认卡片，绝不自己再问一轮「是否确认」，\
            也绝不在未执行成功前声称采购单「已下」或编造单号。建单后单据为草稿；\
            查询采购单进度/到货情况/内容用 query_purchase_order（传单据号如 PO-202606-0001），\
            返回供应商、各行商品与订购量/单价/金额、已到货量/未到货量、单据状态。
            - 采购收货与发票（M3-T11，整条链全部高风险，逐步走确认卡片）：审核采购订单\
            （approve_purchase_order，DRAFT→APPROVED，审核后才能收货）→ 收货入库\
            （create_purchase_receipt 建采购入库单草稿，引用已审核 PO 号 + 收货仓 + 各行 po_line_no/收货数量/可选收货单价；\
            approve_purchase_receipt 审核；post_purchase_receipt 过账才真正入库、产生 PURCHASE_IN 流水并回写到货量）\
            → 采购发票（create_purchase_invoice 引用已过账入库单号 + 各行 receipt_line_no/开票数量/开票金额，\
            三单匹配开票量≤已收量；approve_purchase_invoice 审核；post_purchase_invoice 过账生成应付账款）。\
            每个高风险工具调用前在 text 复述要点，框架自动出确认卡片，绝不自己再问一轮「是否确认」，\
            绝不在未过账成功前声称「已入库」「已形成应付」。只读：query_purchase_receipt（传 PR- 单号）、\
            query_purchase_invoice（传 PINV- 单号）、query_payables（按供应商/状态查应付台账，登录即可）。
            - 销售订单（create_sales_order 高风险 / query_sales_order 普通）：给某客户下单卖货。\
            建单（create_sales_order）需客户、各行商品/数量/销售单价，客户与商品传名称或编码；下单不动库存\
            （只是销售约定），可用库存不足只提示不阻断（可选传 check_warehouse 让系统对该仓做可用库存检查）。\
            流程同其他创建类：先在 text 复述要点（客户、各行商品/数量/单价）后直接发起调用，框架自动出确认卡片，\
            绝不自己再问一轮"是否确认"，也绝不在未执行成功前声称"已下单"或编造订单号。建单后单据为草稿，\
            还需审核、出库、开票才完成销售。\
            查询销售订单进度/内容用 query_sales_order（传单据号如 SO-202606-0001），返回客户、各行商品/数量/单价/\
            金额/累计发货量/剩余可发量、单据状态与订单总金额。
            - 销售出库与发票（M3-T11，整条链全部高风险，逐步走确认卡片）：审核销售订单\
            （approve_sales_order，DRAFT→APPROVED，审核后才能出库）→ 出库\
            （create_sales_delivery 建出库单草稿，引用已审核 SO 号 + 出库仓 + 各行 so_line_no/商品/发货数量；\
            approve_sales_delivery 审核；post_sales_delivery 过账才真正扣库存、产生 SALES_OUT 流水并按移动加权结转 COGS、\
            回写发货量，库存不足整批回滚）→ 销售发票（create_sales_invoice 引用已过账出库单号 + 各行 delivery_line_no/商品/\
            开票数量/销售单价；approve_sales_invoice 审核；post_sales_invoice 过账生成应收账款）。\
            每个高风险工具调用前在 text 复述要点，框架自动出确认卡片，绝不自己再问一轮「是否确认」，\
            绝不在未过账成功前声称「已出库」「已形成应收」。只读：query_sales_delivery（传 SD- 单号）、\
            query_sales_invoice（传 SINV- 单号）、query_receivables（按客户/状态查应收台账，登录即可）。
            - 数据一致性校验（run_consistency_check，NORMAL，只读）：用户问「账对不对/有没有对不平/\
            库存-成本-应收应付有没有差错/帮我核一下账」时调用，跑库存流水Σ=余额数量、Σ成本=余额金额、\
            余额非负、应付=采购发票额、应收=销售发票额、COGS=出库流水成本、采购/销售三单数量勾稽共多条校验，\
            产出结构化差异报告（只上报不静默修复）。引用返回的真实 break 明细回答，无 break 则告知账实一致。
            - 收付款单（M4-T04，逐步走确认卡片，须 finance:settlement）：收款单——\
            create_collection_receipt（HIGH，建收款单草稿，需资金账户编号+各行应收ID/核销金额）→\
            approve_collection_receipt（HIGH，审核）→ post_collection_receipt（HIGH，过账：同事务核销对应\
            应收账款+生成现金侧 GL 凭证，不可在未过账前声称「款已到账」）；付款单同理：\
            create/approve/post_payment_disbursement（HIGH×3，过账核销应付+现金侧凭证）。\
            只读查询：query_collection_receipts / query_payment_disbursements（NORMAL，需 finance:settlement）。\
            每个高风险工具调用前在 text 复述要点，框架自动出确认卡片，绝不自己再问一轮「是否确认」。
            - 月末关账（M4-T05，须 finance:period）：先用 precheck_period_close（NORMAL，只读）\
            查看指定账期可否关账——返回一致性 ERROR/WARN 清单、损益结转预览、预估净利润，有 ERROR 则关账会被阻塞，\
            须先修复；再用 close_accounting_period（HIGH HITL，不可逆）执行关账，单一事务内完成「一致性闸门→\
            损益结转凭证→试算平衡断言→关账」，任一步失败整体回滚，关账后该账期不可再过账。\
            框架自动出确认卡片，绝不在事务成功前声称「已关账」。
            - 财务查询（M4-T08，NORMAL 只读，须对应 finance 权限，金额以字符串原样引用不换算）：\
            query_receivable_aging（finance:settlement，应收账龄催款，入参 asOf/customerId/page/size）；\
            query_payable_aging（finance:settlement，应付账龄，入参 asOf/supplierId/page/size）；\
            query_balance_sheet（finance:report，资产负债表，入参 period=yyyyMM，必填）；\
            query_income_statement（finance:report，利润表，入参 period，必填）；\
            query_trial_balance（finance:voucher，试算平衡，入参 period，必填）；\
            query_account_balance（finance:voucher，单科目余额，入参 accountCode+period，均必填）；\
            query_receivable_settlements（finance:settlement，应收核销流水，入参 receivableId，必填）；\
            query_payable_settlements（finance:settlement，应付核销流水，入参 payableId，必填）。\
            上述工具均只读，直接调用后引用返回的真实数据回答，无需确认流程。
            - 统一冲销（M4-T07，HIGH 不可逆 HITL，红字冲销不物理删除，须对应权限点）：\
            reverse_voucher（finance:voucher）对已过账凭证生成借贷对调红字凭证；\
            reverse_purchase_receipt（purchase:receipt）采购入库单红冲（反向库存+凭证）；\
            reverse_purchase_invoice（purchase:invoice）采购发票红冲（须应付未核销，否则先冲付款单）；\
            reverse_sales_delivery（sales:delivery）销售出库单红冲（反向库存 COGS+凭证）；\
            reverse_sales_invoice（sales:invoice）销售发票红冲（须应收未核销，否则先冲收款单）；\
            reverse_collection_receipt（finance:settlement）收款单红冲（反向核销应收+现金侧凭证，解锁销售发票红冲）；\
            reverse_payment_disbursement（finance:settlement）付款单红冲（反向核销应付+现金侧凭证，解锁采购发票红冲）；\
            reverse_transfer（inventory:transfer）调拨单红冲（反向两腿库存，不出凭证）；\
            reverse_stock_count（inventory:count）盘点单红冲（反向盘盈/盘亏库存，不出凭证）。\
            所有冲销工具均 HIGH，框架自动出确认卡片，绝不自己再问一轮「是否确认」，\
            绝不在工具实际执行成功前声称「已冲销」；已核销发票须先冲对应收/付款单、再冲发票；\
            关账期内冲销会被拒（须先重开账期，高敏操作）。

            ## 能力边界与流程缺口记录（硬性要求，绝不违反）
            已注册工具就是你能力的全部边界。当你判断用户的需求**当前能力做不到**\
            （没有任何工具能完成，或工具能力明显不覆盖该需求）时，走流程缺口记录通道，\
            把需求结构化提交给开发团队，而不是变通硬做：
            1. 先向用户确认要点：在 text 中复述你理解的【场景】（用户想在什么业务情境下做什么）\
            和【期望效果】（希望系统做到什么），如有理解不准之处请用户指正；
            2. 同一条回复中用 options 给出两个普通选项引导用户决定：\
            「帮我记录这个需求」（id 如 "opt-record-gap"）和「不用了」（id 如 "opt-skip-gap"）。\
            这是普通引导选项，不是高风险操作，不要设置 requiresConfirmation，选项不要带 risk；
            3. 用户同意记录后，调用 record_process_gap 工具落库\
            （title/scenario/expected_behavior/missing_capability/business_module/severity \
            按与用户确认过的要点用中文填写），然后把工具返回的缺口编号（如 GAP-202606-0001）\
            告诉用户，并说明「已记录，开发团队会评估，解决后会通知你」；
            4. 用户选「不用了」则正常继续对话，不要反复推销记录；
            5. 缺口信息已基本明确时不要反复追问细节，一轮确认即可记录；\
            绝不在未调用工具的情况下声称"已记录"或编造缺口编号。
            """;

    /** 语言约定（公共尾部） */
    private static final String SYSTEM_PROMPT_LANGUAGE = """

            ## 语言
            text、label、description、title 等用户可见文案一律使用中文；\
            id、name、action.type 等标识符用英文。
            """;

    private final AgentLoop agentLoop;
    private final AgentReplyJsonCodec codec;
    private final PendingToolCallJsonCodec pendingCodec;
    private final ToolRegistry toolRegistry;
    private final FinalJsonMode finalJsonMode;
    private final int maxIterations;
    private final Duration loopTimeout;
    /** 历史窗口裁剪（M1-T05）；null = 不裁剪（兼容旧构造器的测试场景） */
    private final HistoryTrimmer historyTrimmer;
    /** 摘要回调（M1-T05，单独调一次 LLM 压缩旧轮次）；裁剪开启时必须提供 */
    private final HistorySummarizer historySummarizer;

    /**
     * @param agentLoop     执行循环（M1-T02，LLM 客户端与各校验器已在装配时注入循环）
     * @param codec         选项返回协议编解码
     * @param pendingCodec  高风险待确认现场编解码
     * @param toolRegistry  工具注册表（空 = 行为退化为单轮对话）
     * @param finalJsonMode 终轮 JSON 模式。DeepSeek 实测（2026-06）：response_format=json_object
     *                      与 tools 同时携带不报错但模型稳定不发起工具调用，故默认须用
     *                      {@link FinalJsonMode#JSON_SEPARATE_FINAL_CALL}
     * @param maxIterations 循环最大迭代次数
     * @param loopTimeout   循环整体超时预算（覆盖全部 LLM 调用与工具执行）
     */
    public LlmAgent(AgentLoop agentLoop, AgentReplyJsonCodec codec,
                    PendingToolCallJsonCodec pendingCodec, ToolRegistry toolRegistry,
                    FinalJsonMode finalJsonMode, int maxIterations, Duration loopTimeout) {
        this(agentLoop, codec, pendingCodec, toolRegistry, finalJsonMode,
                maxIterations, loopTimeout, null, null);
    }

    /**
     * 全参构造（M1-T05 会话上下文治理）：
     *
     * @param historyTrimmer    历史窗口裁剪纯函数（null = 不裁剪）；超 token 预算时把最旧的
     *                          若干轮压缩为摘要，摘要随会话落库（history_summary 列，V8 迁移），
     *                          完整历史仍在 agent_message 表、回放不受影响
     * @param historySummarizer 摘要回调（单独调一次 LLM）；摘要失败时本次硬截断兜底，不阻塞对话
     */
    public LlmAgent(AgentLoop agentLoop, AgentReplyJsonCodec codec,
                    PendingToolCallJsonCodec pendingCodec, ToolRegistry toolRegistry,
                    FinalJsonMode finalJsonMode, int maxIterations, Duration loopTimeout,
                    HistoryTrimmer historyTrimmer, HistorySummarizer historySummarizer) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.pendingCodec = Objects.requireNonNull(pendingCodec, "pendingCodec 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.finalJsonMode = Objects.requireNonNull(finalJsonMode, "finalJsonMode 不能为空");
        this.maxIterations = maxIterations;
        this.loopTimeout = loopTimeout;
        this.historyTrimmer = historyTrimmer;
        this.historySummarizer = historySummarizer;
    }

    @Override
    public AgentReply replyToText(AgentSession session, String text) {
        return handle(session, text);
    }

    @Override
    public AgentReply replyToOption(AgentSession session, Option option) {
        // 协议约定前端只回传 optionId，ChatService 已按 id 还原；这里把 label 回灌给模型
        String userText = "[用户点击选项] " + option.label();
        if (session.hasPendingToolCall() && ToolConfirmation.CONFIRM_OPTION_ID.equals(option.id())) {
            // 用户点击「确认执行」：恢复被拦截的高风险调用
            return resumePending(session, userText, true);
        }
        return handle(session, userText);
    }

    @Override
    public AgentReply replyToForm(AgentSession session, String formId, Map<String, String> values) {
        return handle(session, "[用户提交表单 " + formId + "] 表单值：" + toJson(values));
    }

    // ---------------------------------------------------------------- 主流程

    /**
     * 统一入口：存在待确认高风险调用时，任何非「确认」输入（点取消、改发文本、
     * 提交表单）都按取消语义恢复循环（工具一律不执行，告知模型由其向用户说明）；
     * 否则正常从头跑一次执行循环。
     */
    private AgentReply handle(AgentSession session, String currentUserText) {
        if (session.hasPendingToolCall()) {
            return resumePending(session, currentUserText, false);
        }
        try {
            AgentLoopResult result = agentLoop.run(loopRequest(session, currentUserText));
            return toReply(session, result);
        } catch (RuntimeException e) {
            // LLM 调用失败（超时/网络/非 200）/ 循环预算用尽：不把异常抛给用户
            log.error("Agent 执行循环失败，返回兜底回复（sessionId={}）", session.getSessionId(), e);
            return AgentReply.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /** 从会话中的待确认现场恢复循环（confirmed=false 表示取消） */
    private AgentReply resumePending(AgentSession session, String currentUserText, boolean confirmed) {
        PendingToolCall pending;
        try {
            pending = pendingCodec.fromJson(session.getPendingToolCallJson());
        } catch (RuntimeException e) {
            // 现场损坏（不应发生）：清掉并按全新输入处理，避免会话卡死
            log.error("pending_tool_call 反序列化失败，已丢弃现场（sessionId={}）", session.getSessionId(), e);
            session.setPendingToolCallJson(null);
            return handle(session, currentUserText);
        }
        // 先清现场：若恢复过程中再次产生待确认调用，toReply 会重新写入
        session.setPendingToolCallJson(null);
        log.info("恢复高风险工具确认流程（sessionId={}, tool={}, confirmed={}）",
                session.getSessionId(), pending.toolName(), confirmed);
        try {
            AgentLoopResult result = agentLoop.resume(loopRequest(session, currentUserText), pending, confirmed);
            return toReply(session, result);
        } catch (RuntimeException e) {
            log.error("确认流程恢复失败，返回兜底回复（sessionId={}, tool={}）",
                    session.getSessionId(), pending.toolName(), e);
            return AgentReply.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /** 循环结果 → 协议回复：完成则解析最终 JSON；待确认则写入会话现场并出确认卡片 */
    private AgentReply toReply(AgentSession session, AgentLoopResult result) {
        logToolCallRecords(session, result.toolCallRecords());
        if (result.isPendingConfirmation()) {
            PendingToolCall pending = result.pendingToolCall();
            // 现场随 ChatService 落库（agent_session.pending_tool_call，V3 迁移）——杀进程仍可恢复
            session.setPendingToolCallJson(pendingCodec.toJson(pending));
            log.info("高风险工具被框架拦截，等待用户确认（sessionId={}, tool={}, arguments={}）",
                    session.getSessionId(), pending.toolName(), pending.argumentsJson());
            return toConfirmationReply(pending);
        }
        return parseReply(result.finalText());
    }

    /**
     * 待确认现场 → requiresConfirmation=true 的确认卡片（协议 v0.1）。
     * 「确认执行」「取消」用框架固定选项 id（docs/选项返回协议.md「框架级工具确认选项」），
     * 本类凭固定 id 识别确认 / 取消语义。
     */
    private static AgentReply toConfirmationReply(PendingToolCall pending) {
        StringBuilder text = new StringBuilder();
        if (pending.assistantContent() != null && !pending.assistantContent().isBlank()) {
            text.append(pending.assistantContent()).append("\n\n");
        }
        text.append("⚠️ ").append(pending.summary())
                .append("\n\n该操作属于**高风险操作**，需要你确认后系统才会真正执行。");
        return AgentReply.confirmation(text.toString(), List.of(
                Option.highRisk(ToolConfirmation.CONFIRM_OPTION_ID, "确认执行",
                        "确认后系统立即执行该操作", null),
                Option.of(ToolConfirmation.CANCEL_OPTION_ID, "取消")));
    }

    /**
     * 组装一次循环输入：系统提示（+ 历史摘要）+ 裁剪后的历史 + 当前用户输入 +
     * 已注册工具 + 防护参数。
     *
     * <p>会话上下文治理（M1-T05）：历史先经 {@link HistoryTrimmer} 裁剪——估算 token
     * 超预算时最旧若干轮压缩为摘要（摘要写回会话，随 ChatService 落库），摘要作为
     * 系统提示的「早前对话摘要」小节注入；摘要失败则本次硬截断并打 WARN。
     * 裁剪只影响发给 LLM 的内容，agent_message 中的完整历史与回放 API 不受影响。
     */
    private AgentLoopRequest loopRequest(AgentSession session, String currentUserText) {
        // 候选消息：USER / ASSISTANT 进入上下文（SYSTEM/TOOL 历史暂不进入），
        // seq = 列表下标 + 1，与 agent_message.seq 的持久化口径一致（仓储按此插入）
        List<AgentMessage> all = session.getMessages();
        List<HistoryMessage> candidates = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            AgentMessage message = all.get(i);
            if (message.role() == MessageRole.USER || message.role() == MessageRole.ASSISTANT) {
                candidates.add(new HistoryMessage(i + 1, message.role(), message.content()));
            }
        }

        String summary = session.getHistorySummary();
        List<HistoryMessage> window = candidates;
        if (historyTrimmer != null) {
            // forSession：摘要的 LLM 调用观测（M1-T07）记录携带会话 id（purpose=summarize）
            HistoryTrimResult trimmed = historyTrimmer.trim(candidates, summary,
                    session.getSummarizedUntilSeq(),
                    historySummarizer == null ? null : historySummarizer.forSession(session.getSessionId()));
            if (trimmed.summaryUpdated()) {
                // 新摘要写回会话，随本轮 ChatService.save 落库（history_summary 列）
                session.updateHistorySummary(trimmed.summary(), trimmed.summarizedUntilSeq());
                log.info("会话历史已压缩为摘要（sessionId={}, summarizedUntilSeq={}, 摘要长度={} 字符, "
                                + "窗口内消息数={}）", session.getSessionId(), trimmed.summarizedUntilSeq(),
                        trimmed.summary().length(), trimmed.recentMessages().size());
            }
            if (trimmed.hardTruncated()) {
                // 摘要失败兜底：本次只带最近 N 轮，不阻塞对话；完整历史在库中，下一轮重试摘要
                log.warn("历史摘要生成失败，本次硬截断（sessionId={}, 窗口内消息数={}, 原因: {}）",
                        session.getSessionId(), trimmed.recentMessages().size(), trimmed.failureReason());
            }
            summary = trimmed.summary();
            window = trimmed.recentMessages();
        } else if (session.getSummarizedUntilSeq() > 0) {
            // 裁剪未启用但会话带有历史摘要状态（如配置回退）：仍按摘要覆盖范围过滤，保证语义一致
            int untilSeq = session.getSummarizedUntilSeq();
            window = candidates.stream().filter(m -> m.seq() > untilSeq).toList();
        }

        List<LlmMessage> history = new ArrayList<>();
        for (HistoryMessage message : window) {
            // assistant 消息落库时即为 AgentReply 协议 JSON，原样作为 assistant 上下文
            history.add(message.role() == MessageRole.USER
                    ? LlmMessage.user(message.content())
                    : LlmMessage.assistant(message.content()));
        }
        history.add(LlmMessage.user(currentUserText));

        List<Tool> tools = List.copyOf(toolRegistry.all());
        return AgentLoopRequest.builder()
                .systemPrompt(systemPrompt(tools.isEmpty(), summary))
                .history(history)
                .tools(tools)
                // 审计上下文：谁、哪个会话、依据什么指令（CLAUDE.md 原则 3）
                .context(new ToolContext(session.getSessionId(), session.getUserId(), currentUserText))
                .maxIterations(maxIterations)
                .timeout(loopTimeout)
                .finalJsonMode(finalJsonMode)
                .build();
    }

    /** 系统提示 = 主体 + 能力边界（按有无工具二选一）+ 语言约定 + 早前对话摘要（如有） */
    private static String systemPrompt(boolean noTools, String historySummary) {
        String prompt = SYSTEM_PROMPT_BASE
                + (noTools ? SYSTEM_PROMPT_NO_TOOLS : SYSTEM_PROMPT_WITH_TOOLS)
                + SYSTEM_PROMPT_LANGUAGE;
        if (historySummary != null && !historySummary.isBlank()) {
            // 摘要作为 system 上下文注入（M1-T05）：更早的对话已压缩，单据号/金额以摘要为准
            prompt += "\n\n## 早前对话摘要\n本会话更早的对话已被压缩为以下要点"
                    + "（其中的单据号、客户名、金额等业务信息真实有效，可直接引用）：\n"
                    + historySummary;
        }
        return prompt;
    }

    /** 工具调用记录结构化日志（落库已由 AgentInvocationListener 负责，此处保留便于按日志排查） */
    private static void logToolCallRecords(AgentSession session, List<ToolCallRecord> records) {
        for (ToolCallRecord record : records) {
            log.info("工具调用记录（sessionId={}）: tool={}, success={}, elapsedMillis={}, arguments={}, result={}",
                    session.getSessionId(), record.toolName(), record.success(),
                    record.elapsedMillis(), record.argumentsJson(), abbreviate(record.resultContent()));
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    /** 模型输出 → AgentReply；不符合协议时降级为纯文本回复（version 由构造器补 0.1） */
    private AgentReply parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("模型返回空内容，降级为提示文本");
            return AgentReply.text("（模型未返回内容，请重试）");
        }
        try {
            // 防御：即便启用 json_object，仍兼容偶发的代码块包裹
            return codec.fromJson(stripCodeFence(raw));
        } catch (RuntimeException e) {
            log.warn("模型输出不符合选项返回协议，降级为纯文本回复：{}", e.getMessage());
            return AgentReply.text(raw);
        }
    }

    /** 去掉 ```json ... ``` 代码块包裹（防御性处理） */
    private static String stripCodeFence(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int fenceEnd = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && fenceEnd > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, fenceEnd).strip();
            }
        }
        return trimmed;
    }

    private static String toJson(Map<String, String> values) {
        try {
            return PLAIN_MAPPER.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException e) {
            return String.valueOf(values);
        }
    }
}
