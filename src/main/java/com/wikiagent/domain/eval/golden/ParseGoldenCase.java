package com.wikiagent.domain.eval.golden;

import java.util.List;
import java.util.Map;

/**
 * 解析黄金样本（parse-golden.json 数组元素）。
 *
 * @param id          样本 id
 * @param kind        介质说明（pdf-text/pdf-scan/pdf-encrypted/image/audio/doc/xlsx...）
 * @param domain      九大业务域标签（DomainTag code，AC-H4 相关性评审）
 * @param fixturePath 夹具路径：classpath: 前缀读既有二进制固件；synthetic: 前缀由评测器确定性生成
 * @param password    加密 PDF 的正确口令（仅加密样本）
 * @param expects     解析断言
 * @param skipReason  非空表示跳过（缺夹具，不新建二进制）；跳过样本不计入有效条数
 */
public record ParseGoldenCase(String id,
                              String kind,
                              String domain,
                              String fixturePath,
                              String password,
                              Expects expects,
                              String skipReason) {

    /**
     * @param textContains 全文必须全部包含的字符串
     * @param tableRows    跨页/多行列数期望（StitchedTable.rows 或表格文本行数）；null 不校验
     * @param pageCount    页数期望；null 不校验
     * @param fields       字段级断言（value 必须出现在全文中，键仅作说明）
     */
    public record Expects(List<String> textContains,
                          Integer tableRows,
                          Integer pageCount,
                          Map<String, String> fields) {
    }
}
