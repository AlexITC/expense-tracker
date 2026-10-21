package net.wiringbits.actions.documents

import net.wiringbits.apis.{TelegramApi, TextractApiType}
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.config.WebAppConfig
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.models.{Document, Receipt}
import net.wiringbits.repositories.{
  DocumentsRepository,
  ImportRuleRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository,
  TextractCacheRepository
}
import net.wiringbits.services.{DocumentClassifier, DocumentParser, DocumentTelegramNotifier}
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue

import java.time.Clock
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
    telegramReceiptRepository: TelegramReceiptRepository,
    telegramApi: TelegramApi,
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
    textractCacheRepository.find(doc.sha256, TextractApiType.AnalyzeExpense).flatMap {
      case None =>
        logger.error(s"No AnalyzeExpense cache for document ${doc.id} (userId=${receipt.userId}), marking Unsupported")
        for {
          _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Unsupported)
          _ <- notifyTelegramError(doc.id, "Could not parse the receipt: analysis data not found.")
        } yield ()
      case Some(cache) =>
        // Advisory post-OCR classification (fail-open) before parsing the cached Textract result.
        classifyAfterTextract(doc, cache.response).flatMap { _ =>
          DocumentParser.parse(cache.response, doc.kind.entryName) match {
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
                    ruleRepository.all(doc.userId).flatMap { rules =>
                      val matchingRule = ImportRuleMatcher.findReceiptWinner(
                        rules.map(r => ImportRuleMatcher.Rule(r.id, r.action, r.pattern, r.merchantId)),
                        name
                      )
                      matchingRule match {
                        case Some(rule) =>
                          rule.merchantId match {
                            case Some(mid) => receiptRepository.setMerchantId(receipt.id, mid)
                            case None => Future.unit
                          }
                        case None =>
                          merchantsRepository.createPendingIfAbsent(doc.userId, name).flatMap { merchantId =>
                            for {
                              _ <- receiptRepository.setMerchantId(receipt.id, merchantId)
                              exists <- ruleRepository.existsByUserPatternMerchant(doc.userId, name, merchantId)
                              _ <-
                                if (exists) Future.unit
                                else
                                  ruleRepository
                                    .create(
                                      doc.userId,
                                      ImportRuleAction.SetMerchant,
                                      name,
                                      Some(merchantId),
                                      autoCreated = true
                                    )
                                    .map(_ => ())
                            } yield ()
                          }
                      }
                    }
                  case _ => Future.unit
                }
                _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Parsed)
                _ <- telegramReceiptRepository
                  .findByReceiptId(receipt.id)
                  .flatMap {
                    case None => Future.unit
                    case Some(tr) =>
                      val webAppUrl = s"${webAppConfig.host}/api/telegram/webapp?receipt=${receipt.id}"
                      val summary =
                        parsed.vendor_name.map(n => s"Receipt from $n parsed.").getOrElse("Receipt parsed.")
                      telegramApi
                        .sendMessageWithWebAppButton(tr.chatId, summary, webAppUrl, "Create Expense")
                        .map(_ => ())
                  }
                  .recover { case e =>
                    logger.warn(s"Failed to notify Telegram for document ${doc.id}: ${e.getMessage}")
                  }
              } yield ()
            case Left(DocumentParser.UnsupportedKind(kind)) =>
              logger.warn(s"Document ${doc.id} (userId=${receipt.userId}) has unsupported kind '$kind'")
              for {
                _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Unsupported)
                _ <- notifyTelegramError(doc.id, "Could not parse the receipt: unsupported document format.")
              } yield ()
            case Left(DocumentParser.ParseError(reason)) =>
              logger.warn(s"Document ${doc.id} (userId=${receipt.userId}) parse failed: $reason")
              for {
                _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.ParseFailed)
                _ <- notifyTelegramError(doc.id, "Could not parse the receipt.")
              } yield ()
          }
        }
    }

  /** Classifies a document that had no text layer to classify before Textract (photos, scans) from the Textract text.
    * ADVISORY and FAIL-OPEN, on purpose: Textract has already been paid for, so the document is parsed whatever the
    * answer; a wrong document only gets a Telegram hint, and any failure here is logged and ignored.
    */
  private def classifyAfterTextract(doc: Document, response: JsValue): Future[Unit] =
    if (doc.aiClassification.nonEmpty) Future.unit
    else
      Future
        .delegate {
          val text = DocumentParser.reconstructText(response)
          // Textract found no field values: there is nothing to judge, so don't store a guess about an empty state.
          if (text.isBlank) {
            logger.info(s"userId=${doc.userId}, document ${doc.id} has no Textract text, skipping classification")
            Future.unit
          } else
            for {
              result <- documentClassifier.classify(doc.userId, s"document ${doc.id}", text)
              _ <- result.persistWith(documentsRepository.updateClassification(doc.id, doc.userId, _))
              _ <- result.rejection(DocumentClassifier.acceptedFor(doc.kind)) match {
                case Some(cls) =>
                  logger.info(s"userId=${doc.userId}, document ${doc.id} looks like $cls after Textract, advising user")
                  notifier.notify(doc.id, doc.userId, DocumentTelegramNotifier.notATicketMessage(cls))
                case None => Future.unit
              }
            } yield ()
        }
        .recover { case NonFatal(e) =>
          logger.warn(
            s"userId=${doc.userId}, document ${doc.id} advisory classification failed: ${e.getClass.getSimpleName}"
          )
        }

  private def notifyTelegramError(documentId: UUID, text: String): Future[Unit] =
    receiptRepository
      .findByDocumentId(documentId)
      .flatMap {
        case None => Future.unit
        case Some(receipt) =>
          telegramReceiptRepository.findByReceiptId(receipt.id).flatMap {
            case None => Future.unit
            case Some(tr) => telegramApi.sendMessage(tr.chatId, text)
          }
      }
      .recover { case e => logger.warn(s"Failed to notify Telegram for document $documentId: ${e.getMessage}") }
}
