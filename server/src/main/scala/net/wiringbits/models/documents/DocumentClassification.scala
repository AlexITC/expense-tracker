package net.wiringbits.models.documents

/** The AI document classifier's answer: the document type, and whether the text looks like a prompt-injection attempt
  * (an advisory signal only, it never blocks a document).
  */
case class DocumentClassification(
    documentClass: DocumentClass,
    confidence: BigDecimal,
    injectionSuspected: Boolean,
    injectionConfidence: BigDecimal
)
