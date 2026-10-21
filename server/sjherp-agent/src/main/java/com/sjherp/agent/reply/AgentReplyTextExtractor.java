package com.sjherp.agent.reply;

/**
 * 从终轮协议 JSON 的流式碎片中增量抽出 {@code text} 字段（选项返回协议 v0.2）。
 *
 * <p>Agent 循环只编排消息流，终轮模型输出仍是完整 AgentReply JSON。本抽取器在
 * 顶层找到 {@code "text": "..."} 后按 JSON 字符串转义规则边收边吐增量，供前端打字机
 * 渲染；{@code options}/{@code form} 留到解析完整 JSON 后由 {@code complete} 事件下发，
 * 避免流式半成品破坏 HITL 确认卡片。
 *
 * <p>零依赖纯 Java。无法抽出（非 JSON、缺 text、残缺转义）时不吐增量，由上层在
 * 完整解析后一次性补发正文。
 */
public final class AgentReplyTextExtractor {

    private enum State {
        PREAMBLE,
        SCAN,
        IN_KEY,
        AFTER_KEY,
        SKIP_VALUE,
        IN_TEXT,
        DONE
    }

    private State state = State.PREAMBLE;
    private int depth;
    private int skipDepth;
    private boolean inString;
    private boolean escape;
    private boolean unicodePending;
    private int unicodeRemain;
    private int unicodeAcc;
    private boolean skipToText;
    private boolean textKey;
    private final StringBuilder key = new StringBuilder();
    private final StringBuilder extracted = new StringBuilder();

    /** 已抽出的正文（按到达顺序累积，等于各次 {@link #feed(String)} 返回值的拼接） */
    public String extractedText() {
        return extracted.toString();
    }

    /**
     * 喂入一段模型输出碎片，返回本次新抽出的正文（可能为空串）。
     */
    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty() || state == State.DONE) {
            return "";
        }
        StringBuilder newly = new StringBuilder();
        for (int i = 0; i < chunk.length(); i++) {
            handle(chunk.charAt(i), newly);
            if (state == State.DONE) {
                break;
            }
        }
        return newly.toString();
    }

    private void handle(char c, StringBuilder newly) {
        switch (state) {
            case PREAMBLE -> handlePreamble(c);
            case IN_TEXT -> handleText(c, newly);
            case IN_KEY -> handleKey(c);
            case SKIP_VALUE -> handleSkipValue(c);
            case AFTER_KEY -> handleAfterKey(c);
            case SCAN -> handleScan(c);
            case DONE -> {
                // 已结束
            }
        }
    }

    private void handlePreamble(char c) {
        if (Character.isWhitespace(c) || c == '`') {
            return;
        }
        // 跳过 ```json 语言标记
        if (c == 'j' || c == 'J' || c == 's' || c == 'o' || c == 'n'
                || c == 'S' || c == 'O' || c == 'N') {
            return;
        }
        if (c == '{') {
            depth = 1;
            state = State.SCAN;
        }
        // 其它字符视为非 JSON，保持 PREAMBLE，整段不抽
    }

    private void handleScan(char c) {
        if (Character.isWhitespace(c) || c == ',') {
            return;
        }
        if (c == '"') {
            inString = true;
            escape = false;
            key.setLength(0);
            state = State.IN_KEY;
            return;
        }
        if (c == '}') {
            depth--;
            state = State.DONE;
        }
    }

    private void handleKey(char c) {
        if (escape) {
            escape = false;
            key.append(unescape(c));
            return;
        }
        if (c == '\\') {
            escape = true;
            return;
        }
        if (c == '"') {
            inString = false;
            textKey = "text".equals(key.toString());
            state = State.AFTER_KEY;
            return;
        }
        key.append(c);
    }

    private void handleAfterKey(char c) {
        if (Character.isWhitespace(c)) {
            return;
        }
        if (c != ':') {
            state = State.DONE;
            return;
        }
        if (textKey && depth == 1) {
            state = State.IN_TEXT;
            skipToText = true;
        } else {
            state = State.SKIP_VALUE;
            skipDepth = 0;
            inString = false;
            escape = false;
        }
    }

    private void handleSkipValue(char c) {
        if (inString) {
            if (escape) {
                escape = false;
                return;
            }
            if (c == '\\') {
                escape = true;
                return;
            }
            if (c == '"') {
                inString = false;
                if (skipDepth == 0) {
                    state = State.SCAN;
                }
            }
            return;
        }
        if (Character.isWhitespace(c)) {
            return;
        }
        if (c == '"') {
            inString = true;
            escape = false;
            return;
        }
        if (c == '{' || c == '[') {
            skipDepth++;
            return;
        }
        if (c == '}' || c == ']') {
            if (skipDepth > 0) {
                skipDepth--;
                if (skipDepth == 0) {
                    state = State.SCAN;
                }
                return;
            }
            state = State.DONE;
            return;
        }
        if (c == ',' && skipDepth == 0) {
            state = State.SCAN;
        }
        // 数字 / true / false / null：继续读到逗号或结束括号
    }

    private void handleText(char c, StringBuilder newly) {
        if (skipToText) {
            if (Character.isWhitespace(c)) {
                return;
            }
            if (c == '"') {
                skipToText = false;
                return;
            }
            state = State.DONE;
            return;
        }
        if (unicodePending) {
            if (!isHex(c)) {
                state = State.DONE;
                return;
            }
            unicodeAcc = (unicodeAcc << 4) + hexValue(c);
            unicodeRemain--;
            if (unicodeRemain == 0) {
                unicodePending = false;
                appendText((char) unicodeAcc, newly);
                unicodeAcc = 0;
            }
            return;
        }
        if (escape) {
            escape = false;
            if (c == 'u') {
                unicodePending = true;
                unicodeRemain = 4;
                unicodeAcc = 0;
                return;
            }
            appendText(unescape(c), newly);
            return;
        }
        if (c == '\\') {
            escape = true;
            return;
        }
        if (c == '"') {
            state = State.DONE;
            return;
        }
        appendText(c, newly);
    }

    private void appendText(char c, StringBuilder newly) {
        extracted.append(c);
        newly.append(c);
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> c;
        };
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }
}
