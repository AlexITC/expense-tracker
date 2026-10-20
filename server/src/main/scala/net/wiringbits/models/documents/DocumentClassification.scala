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

object DocumentClassification {

  /** Rebuilds a stored classification from its four columns, which are set together. */
  def fromColumns(
      documentClass: Option[DocumentClass],
      confidence: Option[BigDecimal],
      injectionSuspected: Option[Boolean],
      injectionConfidence: Option[BigDecimal]
  ): Option[DocumentClassification] =
    for {
      documentClass <- documentClass
      confidence <- confidence
      injectionSuspected <- injectionSuspected
      injectionConfidence <- injectionConfidence
    } yield DocumentClassification(documentClass, confidence, injectionSuspected, injectionConfidence)
}
