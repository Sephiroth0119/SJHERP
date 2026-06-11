package com.sjherp.domain.common.numbering;

import java.time.YearMonth;

/**
 * 单据编号生成器接口。
 *
 * <p>所有业务单据的编号都经由本接口生成，保证全局格式统一
 * （前缀-年月-序号，如 PO-202606-0001）且不重号。
 */
public interface DocumentNumberGenerator {

    /** 按规则生成下一个单据号（年月取当前系统时间） */
    String generate(DocumentNumberRule rule);

    /** 按规则与指定年月生成下一个单据号（补录历史单据等场景） */
    String generate(DocumentNumberRule rule, YearMonth yearMonth);
}
