package com.sjherp.app.gl;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountNotFoundException;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.BalanceDirection;
import com.sjherp.domain.identity.Role;

/**
 * GlAccountController MockMvc 切片测试（M4-T01 §7）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link GlExceptionHandler} 通过
 * {@code setControllerAdvice} 接入。{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 权限测试由专项 {@code @WebMvcTest} 覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>建科目 → 201，返回编码/名称/类别/层级/末级等字段；</li>
 *   <li>建科目缺必填字段 → 400（Bean Validation）；</li>
 *   <li>科目编码重复/类别非法等业务拒绝 → 400（IllegalArgumentException）；</li>
 *   <li>停用非预置科目 → 200；停用预置科目 → 400（IllegalArgumentException）；</li>
 *   <li>启用科目 → 200；重复停用 → 400；</li>
 *   <li>科目不存在 → 404；</li>
 *   <li>科目列表（全部/末级/按类别过滤/类别参数非法）→ 200/400；</li>
 *   <li>科目详情 → 200，枚举 label 正确（type/typeLabel/balanceDir/balanceDirLabel）。</li>
 * </ul>
 */
class GlAccountControllerTest {

    private AccountAppService accountAppService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountAppService = Mockito.mock(AccountAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GlAccountController(accountAppService))
                .setControllerAdvice(new GlExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接将认证态注入 SecurityContextHolder
        // 供控制器内 CurrentUser.operator() 解析登录名
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝",
                Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助方法

    /**
     * 从工厂重建科目（restore 不重跑业务校验），方便构造 stub 返回值。
     */
    private static Account buildAccount(String code, String name, AccountType type,
                                        BalanceDirection dir, int level, boolean isLeaf,
                                        boolean enabled, boolean isPreset) {
        Instant now = Instant.now();
        return Account.restore(1L, code, name, type, dir, null, level, isLeaf, enabled, isPreset,
                "alice", now, "alice", now);
    }

    // ================================================================ 1. 建科目

    /**
     * 建科目成功 → 201，返回编码/名称/类别标签/末级/启用/非预置。
     */
    @Test
    void 建科目_201_返回科目信息() throws Exception {
        Account account = buildAccount("9001", "测试科目", AccountType.ASSET, BalanceDirection.DEBIT,
                1, true, true, false);
        Mockito.when(accountAppService.create(anyString(), anyString(), anyString(), anyString(),
                        Mockito.isNull(), anyBoolean(), anyString()))
                .thenReturn(account);

        mockMvc.perform(post("/api/gl/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "9001",
                                    "name": "测试科目",
                                    "type": "ASSET",
                                    "balanceDir": "DEBIT",
                                    "isLeaf": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("9001"))
                .andExpect(jsonPath("$.name").value("测试科目"))
                .andExpect(jsonPath("$.type").value("ASSET"))
                .andExpect(jsonPath("$.typeLabel").value("资产"))
                .andExpect(jsonPath("$.balanceDir").value("DEBIT"))
                .andExpect(jsonPath("$.balanceDirLabel").value("借"))
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.leaf").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value(false));
    }

    /**
     * 建科目请求体缺必填 code → Bean Validation @NotNull → 400。
     */
    @Test
    void 建科目缺code_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/gl/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "测试科目",
                                    "type": "ASSET",
                                    "balanceDir": "DEBIT",
                                    "isLeaf": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(accountAppService);
    }

    /**
     * 建科目缺必填 isLeaf → Bean Validation @NotNull → 400。
     */
    @Test
    void 建科目缺isLeaf_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/gl/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "9001",
                                    "name": "测试科目",
                                    "type": "ASSET",
                                    "balanceDir": "DEBIT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(accountAppService);
    }

    /**
     * 科目编码重复 → AppService/领域层抛 IllegalArgumentException → 400。
     */
    @Test
    void 建科目编码重复_400() throws Exception {
        Mockito.when(accountAppService.create(anyString(), anyString(), anyString(), anyString(),
                        Mockito.isNull(), anyBoolean(), anyString()))
                .thenThrow(new IllegalArgumentException("科目编码已存在: 1001"));

        mockMvc.perform(post("/api/gl/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "1001",
                                    "name": "库存现金（重复）",
                                    "type": "ASSET",
                                    "balanceDir": "DEBIT",
                                    "isLeaf": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    /**
     * 建科目类别字符串非法 → AppService 解析抛 IllegalArgumentException → 400。
     * 注意：type 字段校验在 AccountAppService.parseType 中，控制器直接透传字符串给 AppService。
     */
    @Test
    void 建科目类别非法_400() throws Exception {
        Mockito.when(accountAppService.create(anyString(), anyString(), anyString(), anyString(),
                        Mockito.isNull(), anyBoolean(), anyString()))
                .thenThrow(new IllegalArgumentException(
                        "科目类别非法（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）: INVALID_TYPE"));

        mockMvc.perform(post("/api/gl/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "9999",
                                    "name": "非法类别",
                                    "type": "INVALID_TYPE",
                                    "balanceDir": "DEBIT",
                                    "isLeaf": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 2. 停用 / 启用

    /**
     * 停用非预置科目 → 200，状态变为停用（enabled=false）。
     */
    @Test
    void 停用非预置科目_200() throws Exception {
        Account disabled = buildAccount("9001", "测试科目", AccountType.ASSET, BalanceDirection.DEBIT,
                1, true, false, false);
        Mockito.when(accountAppService.disable(anyString(), anyString()))
                .thenReturn(disabled);

        mockMvc.perform(post("/api/gl/accounts/9001/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("9001"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value(false));
    }

    /**
     * 停用预置科目 → AppService/领域层 Account.disable 抛 IllegalArgumentException
     * → 400（预置科目守门，CLAUDE.md 原则 2：账表勾稽口径稳定）。
     */
    @Test
    void 停用预置科目_400_预置守门拒绝() throws Exception {
        Mockito.when(accountAppService.disable(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("预置科目[1001] 不可停用"));

        mockMvc.perform(post("/api/gl/accounts/1001/disable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    /**
     * 启用已停用科目 → 200，状态恢复为启用（enabled=true）。
     */
    @Test
    void 启用科目_200() throws Exception {
        Account enabled = buildAccount("9001", "测试科目", AccountType.ASSET, BalanceDirection.DEBIT,
                1, true, true, false);
        Mockito.when(accountAppService.enable(anyString(), anyString()))
                .thenReturn(enabled);

        mockMvc.perform(post("/api/gl/accounts/9001/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("9001"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /**
     * 重复停用（已停用状态再停用）→ 400（领域层 Account.disable 拒绝重复停用）。
     */
    @Test
    void 重复停用_400() throws Exception {
        Mockito.when(accountAppService.disable(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("科目[9001] 已是停用状态，无需重复停用"));

        mockMvc.perform(post("/api/gl/accounts/9001/disable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 3. 科目不存在

    /**
     * 按编码查不存在科目 → AccountNotFoundException → 404。
     */
    @Test
    void 科目不存在_404() throws Exception {
        Mockito.when(accountAppService.get(anyString()))
                .thenThrow(new AccountNotFoundException("9999"));

        mockMvc.perform(get("/api/gl/accounts/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 4. 科目列表

    /**
     * 全部科目列表（leafOnly=false 默认）→ 200，返回 JSON 数组（含非末级）。
     */
    @Test
    void 全部科目列表_200_含非末级() throws Exception {
        List<Account> accounts = List.of(
                buildAccount("2221", "应交税费", AccountType.LIABILITY, BalanceDirection.CREDIT,
                        1, false, true, true),
                buildAccount("222101", "应交税费—应交增值税", AccountType.LIABILITY, BalanceDirection.CREDIT,
                        2, true, true, true));
        Mockito.when(accountAppService.listAll()).thenReturn(accounts);

        mockMvc.perform(get("/api/gl/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("2221"))
                .andExpect(jsonPath("$[0].leaf").value(false))
                .andExpect(jsonPath("$[1].code").value("222101"))
                .andExpect(jsonPath("$[1].leaf").value(true));
    }

    /**
     * 末级科目列表（leafOnly=true）→ 200，只返回末级科目。
     */
    @Test
    void 末级科目列表_leafOnly_200_仅末级() throws Exception {
        List<Account> leafAccounts = List.of(
                buildAccount("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                        1, true, true, true),
                buildAccount("1002", "银行存款", AccountType.ASSET, BalanceDirection.DEBIT,
                        1, true, true, true));
        Mockito.when(accountAppService.listLeaf()).thenReturn(leafAccounts);

        mockMvc.perform(get("/api/gl/accounts?leafOnly=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("1001"))
                .andExpect(jsonPath("$[0].leaf").value(true))
                .andExpect(jsonPath("$[1].code").value("1002"))
                .andExpect(jsonPath("$[1].leaf").value(true));
    }

    /**
     * 科目列表按类别过滤（type=ASSET）→ 200，只返回 ASSET 类科目（控制器层过滤）。
     */
    @Test
    void 科目列表按类别过滤_200_只含指定类别() throws Exception {
        List<Account> allAccounts = List.of(
                buildAccount("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                        1, true, true, true),
                buildAccount("2202", "应付账款", AccountType.LIABILITY, BalanceDirection.CREDIT,
                        1, true, true, true));
        Mockito.when(accountAppService.listAll()).thenReturn(allAccounts);

        mockMvc.perform(get("/api/gl/accounts?type=ASSET"))
                .andExpect(status().isOk())
                // 仅返回 ASSET 类（2202 LIABILITY 被控制器过滤掉）
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("1001"))
                .andExpect(jsonPath("$[0].type").value("ASSET"));
    }

    /**
     * 科目类别过滤参数非法（type=INVALID）→ 400（控制器层 parseTypeFilter 抛
     * IllegalArgumentException → GlExceptionHandler 映射 400）。
     */
    @Test
    void 科目列表类别参数非法_400() throws Exception {
        mockMvc.perform(get("/api/gl/accounts?type=INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 5. 科目详情

    /**
     * 科目详情（预置末级资产科目）→ 200，断言 type/typeLabel/balanceDir/balanceDirLabel/preset。
     */
    @Test
    void 科目详情_200_字段完整() throws Exception {
        Account account = buildAccount("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                1, true, true, true);
        Mockito.when(accountAppService.get(anyString())).thenReturn(account);

        mockMvc.perform(get("/api/gl/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("库存现金"))
                .andExpect(jsonPath("$.type").value("ASSET"))
                .andExpect(jsonPath("$.typeLabel").value("资产"))
                .andExpect(jsonPath("$.balanceDir").value("DEBIT"))
                .andExpect(jsonPath("$.balanceDirLabel").value("借"))
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.leaf").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value(true));
    }

    /**
     * 负债类贷方科目详情 → typeLabel="负债"、balanceDirLabel="贷"（枚举 label 正确映射）。
     */
    @Test
    void 科目详情_负债贷方_label正确() throws Exception {
        Account account = buildAccount("2202", "应付账款", AccountType.LIABILITY, BalanceDirection.CREDIT,
                1, true, true, true);
        Mockito.when(accountAppService.get(anyString())).thenReturn(account);

        mockMvc.perform(get("/api/gl/accounts/2202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LIABILITY"))
                .andExpect(jsonPath("$.typeLabel").value("负债"))
                .andExpect(jsonPath("$.balanceDir").value("CREDIT"))
                .andExpect(jsonPath("$.balanceDirLabel").value("贷"));
    }
}
