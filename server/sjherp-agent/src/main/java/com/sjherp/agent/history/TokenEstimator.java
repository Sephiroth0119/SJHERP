package com.sjherp.agent.history;

/**
 * Token 数量启发式估算（M1-T05 会话上下文治理，零依赖，不引入任何 tokenizer）。
 *
 * <p><b>注意：这是粗略估算，不是精确 token 数。</b>规则：
 * <ul>
 *   <li>CJK / 全角字符（中文、中文标点等）按 1 字 ≈ 1 token；</li>
 *   <li>其余字符（英文、数字、半角标点、空白）按 4 字符 ≈ 1 token（每字符 0.25），向上取整。</li>
 * </ul>
 * 该口径与 DeepSeek / OpenAI 系 tokenizer 的实际值同数量级（中文略偏高、英文略偏低），
 * 仅用于历史窗口裁剪的触发判断，预算（history-token-budget）本身留有余量，误差可接受。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 估算一段文本的 token 数；null / 空串返回 0 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjkOrFullWidth(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        // 非 CJK 部分按每 4 字符 1 token 向上取整
        return cjk + (other + 3) / 4;
    }

    /**
     * 粗判 CJK / 全角字符：CJK 部首与汉字区（含中文标点 0x3000-0x303F）、
     * 兼容汉字区、全角形式区。代理对（emoji 等）按普通字符计——罕见，误差可忽略。
     */
    private static boolean isCjkOrFullWidth(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
