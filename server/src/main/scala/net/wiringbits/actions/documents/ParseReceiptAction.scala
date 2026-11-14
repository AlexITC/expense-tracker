package net.wiringbits.actions.documents

import io.scalaland.chimney.dsl.*
import net.wiringbits.apis.{TelegramApi, TextractApiType}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.config.WebAppConfig
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.models.{Document, Receipt}
import net.wiringbits.repositories.{
  DocumentsRepository,
  ImportRuleRepository,
  MerchantsRepository,
  ReceiptRepository,
  TextractCacheRepository
}
import net.wiringbits.services.{DocumentClassifier, DocumentParser, DocumentTelegramNotifier}
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue

import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class ParseReceiptAction @Inject() (
    documentsRepository: DocumentsRepository,
    receiptRepository: ReceiptRepository,
    merchantsRepository: MerchantsRepository,
    ruleRepository: ImportRuleRepository,
    textractCacheRepository: TextractCacheRepository,
    webAppConfig: WebAppConfig,
    documentClassifier: DocumentClassifier,
    notifier: DocumentTelegramNotifier
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(documentId: UUID): Future[Unit] =
    for {
      docOpt <- documentsRepository.findById(documentId)
      doc = docOpt.getOrElse(throw new RuntimeException(s"Document not found: $documentId"))
      receiptOpt <- receiptRepository.findByDocumentId(documentId)
      receipt = receiptOpt.getOrElse(throw new RuntimeException(s"Receipt not found for document $documentId"))
      _ <-
        if (receipt.status == DocumentStatus.Parsed) {
          logger.info(s"Receipt ${receipt.id} (userId=${receipt.userId}) already Parsed, skipping")
          Future.unit
        } else parse(doc, receipt)
    } yield ()

  private def parse(doc: Document, receipt: Receipt): Future[Unit] =
    textractCacheRepository.find(sha256 = doc.sha256, apiType = TextractApiType.AnalyzeExpense).flatMap {
      case None =>
        logger.error(s"No AnalyzeExpense cache for document ${doc.id} (userId=${receipt.userId}), marking Unsupported")
        for {
          _ <- receiptRepository.updateStatus(id = receipt.id, userId = receipt.userId, DocumentStatus.Unsupported)
          _ <- notifier.updateReceiptMessage(
            documentId = doc.id,
            userId = receipt.userId,
            text = "Could not parse the receipt: analysis data not found.",
            button = None
          )
        } yield ()
      case Some(cache) =>
        // Advisory post-OCR classification (fail-open) before parsing the cached Textract result.
        classifyAfterTextract(doc, cache.response).flatMap { hint =>
          DocumentParser.parse(cache.response, doc.kind) match {
            case Right(parsed) =>
              val date =
                parsed.transaction_date.flatMap(d => scala.util.Try(java.time.LocalDate.parse(d)).toOption)
              val time =
                parsed.transaction_time.flatMap(t => scala.util.Try(java.time.LocalTime.parse(t)).toOption)
              for {
                _ <- receiptRepository.updateParsed(
                  id = receipt.id,
                  amount = parsed.amount,
                  date = date,
                  time = time,
                  merchantName = parsed.vendor_name,
                  taxId = parsed.tax_id,
                  parserVersion = Some(DocumentParser.currentVersion)
                )
                _ <- parsed.vendor_name match {
                  case Some(name) if name.nonEmpty =>
                    ruleRepository.all(doc.userId).zip(merchantsRepository.all(doc.userId)).flatMap {
                      case (rules, merchants) =>
                        val matchingRule = ImportRuleMatcher.findReceiptWinner(
                          ImportRuleMatcher.effectiveRules(
                            rules.map(_.transformInto[ImportRuleMatcher.Rule]),
                            merchants.map(m => m.id -> m.name)
                          ),
                          name,
                          parsed.amount
                        )
                        matchingRule match {
                          case Some(rule) =>
                            rule.action.merchantIdOpt match {
                              case Some(mid) => receiptRepository.setMerchantId(id = receipt.id, merchantId = mid)
                              case None => Future.unit
                            }
                          case None =>
                            merchantsRepository.createPendingIfAbsent(doc.userId, name).flatMap { merchantId =>
                              for {
                                _ <- receiptRepository.setMerchantId(id = receipt.id, merchantId = merchantId)
                                _ <- ruleRepository
                                  .createSetMerchantIfAbsent(userId = doc.userId, merchantId = merchantId, name)
                              } yield ()
                            }
                        }
                    }
                  case _ => Future.unit
                }
                _ <- receiptRepository.updateStatus(id = receipt.id, userId = receipt.userId, DocumentStatus.Parsed)
                _ <- notifier.updateReceiptMessage(
                  documentId = doc.id,
                  userId = receipt.userId,
                  text = (hint.toList :+ readySummary(parsed, date)).mkString("\n"),
                  button = Some(
                    TelegramApi.WebAppButton(
                      label = "Create Expense",
                      url = s"${webAppConfig.host}/api/telegram/webapp?receipt=${receipt.id}"
                    )
                  )
                )
              } yield ()
            case Left(DocumentParser.UnsupportedKind(kind)) =>
              logger.warn(s"Document ${doc.id} (userId=${receipt.userId}) has unsupported kind '$kind'")
              for {
                _ <- receiptRepository
                  .updateStatus(id = receipt.id, userId = receipt.userId, DocumentStatus.Unsupported)
                _ <- notifier.updateReceiptMessage(
                  documentId = doc.id,
                  userId = receipt.userId,
                  text = "Could not parse the receipt: unsupported document format.",
                  button = None
                )
              } yield ()
            case Left(DocumentParser.ParseError(reason)) =>
              logger.warn(s"Document ${doc.id} (userId=${receipt.userId}) parse failed: $reason")
              for {
                _ <- receiptRepository
                  .updateStatus(id = receipt.id, userId = receipt.userId, DocumentStatus.ParseFailed)
                _ <- notifier.updateReceiptMessage(
                  documentId = doc.id,
                  userId = receipt.userId,
                  text = "Could not parse the receipt.",
                  button = None
                )
              } yield ()
          }
        }
    }

  /** Classifies a document that had no text layer to classify before Textract (photos, scans) from the Textract text.
    * ADVISORY and FAIL-OPEN, on purpose: Textract has already been paid for, so the document is parsed whatever the
    * answer; a wrong document only gets a hint shown with the parsed receipt, and any failure here is logged and
    * ignored.
    */
  private def classifyAfterTextract(doc: Document, response: JsValue): Future[Option[String]] =
    if (doc.aiClassification.nonEmpty) Future.successful(None)
    else
      Future
        .delegate {
          val text = DocumentParser.reconstructText(response)
          // Textract found no field values: there is nothing to judge, so don't store a guess about an empty state.
          if (text.isBlank) {
            logger.info(s"userId=${doc.userId}, document ${doc.id} has no Textract text, skipping classification")
            Future.successful(None)
          } else
            for {
              result <- documentClassifier.classify(doc.userId, subject = s"document ${doc.id}", text = text)
              _ <- result.persistWith(c =>
                documentsRepository.updateClassification(id = doc.id, userId = doc.userId, c)
              )
            } yield result.rejection(DocumentClassifier.acceptedFor(doc.kind)).map { cls =>
              logger.info(s"userId=${doc.userId}, document ${doc.id} looks like $cls after Textract, advising user")
              DocumentTelegramNotifier.notATicketMessage(cls)
            }
        }
        .recover { case NonFatal(e) =>
          logger.warn(
            s"userId=${doc.userId}, document ${doc.id} advisory classification failed: ${e.getClass.getSimpleName}"
          )
          None
        }

  // Only fields the parser found are shown; a missing field is left out rather than guessed. The parser reports an
  // unreadable total as zero, so a zero total (and the currency attached to it) is treated as not read.
  private def readySummary(parsed: DocumentParser.ParsedData, date: Option[LocalDate]): String = {
    val vendor = parsed.vendor_name.filter(_.nonEmpty)
    val total =
      parsed.amount.filter(_.signum != 0).map(a => (a.bigDecimal.toPlainString :: parsed.currency.toList).mkString(" "))
    val items = Option(parsed.line_items.size).filter(_ > 0).map(n => if (n == 1) "1 item" else s"$n items")
    val fields = vendor.toList ++ total ++ date.map(_.toString) ++ items
    if (fields.isEmpty) "Receipt ready." else s"Receipt ready: ${fields.mkString(" · ")}"
  }
}
