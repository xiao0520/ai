// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** kind 的封闭判别体，未知类型必须拒绝。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = DocxBlockLocator.class, name = "DOCX_BLOCK"),
    @JsonSubTypes.Type(value = ImageLocator.class, name = "IMAGE"),
    @JsonSubTypes.Type(value = PdfPageLocator.class, name = "PDF_PAGE"),
    @JsonSubTypes.Type(value = PresentationBlockLocator.class, name = "PRESENTATION_BLOCK"),
    @JsonSubTypes.Type(value = SpreadsheetRangeLocator.class, name = "SPREADSHEET_RANGE"),
    @JsonSubTypes.Type(value = TableRangeLocator.class, name = "TABLE_RANGE"),
    @JsonSubTypes.Type(value = TextRangeLocator.class, name = "TEXT_RANGE")
})
public sealed interface SourceLocationLocator permits DocxBlockLocator, ImageLocator, PdfPageLocator, PresentationBlockLocator, SpreadsheetRangeLocator, TableRangeLocator, TextRangeLocator {
}
