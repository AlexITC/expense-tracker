package net.wiringbits.services

import net.wiringbits.apis.{JevChoice, JevClient, JevRateLimitedException}
import net.wiringbits.config.JevConfig
import net.wiringbits.models.JevApiKey
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification}
import net.wiringbits.services.DocumentClassifier.{Classified, Inconclusive}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class DocumentClassifierSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.milliseconds)

  private case class Call(state: String, questionName: String, instructions: String, criteria: Map[String, String])

  // Records every call and answers it with `respond(questionName)`.
  private class StubJevClient(respond: String => Future[JevChoice]) extends JevClient {
    val calls: mutable.ListBuffer[Call] = mutable.ListBuffer.empty

    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = {
      calls += Call(state, questionName, instructions, criteria)
      respond(questionName)
    }
  }

  private val userId = UUID.randomUUID()
  private val text = "TIENDA EJEMPLO S.L. Leche 1,20 Pan 0,90 TOTAL 2,10 EUR"

  private def config(mode: JevConfig.Mode = JevConfig.Real) =
    JevConfig(JevApiKey("test"), "https://example.invalid", "jev-test", mode, minConfidence = BigDecimal("0.85"))

  // TYPE questions get `typeChoice`, INJECTION questions get `injectionChoice`, each with its confidence.
  private def stub(
      typeChoice: String,
      typeConfidence: BigDecimal = BigDecimal("0.95"),
      injectionChoice: String = "clean",
      injectionConfidence: BigDecimal = BigDecimal("0.99")
  ) =
    new StubJevClient({
      case "document_type" => Future.successful(JevChoice(typeChoice, typeConfidence, Map.empty))
      case "prompt_injection" => Future.successful(JevChoice(injectionChoice, injectionConfidence, Map.empty))
      case other => Future.failed(new IllegalStateException(s"unexpected $other question"))
    })

  private def classify(client: JevClient, mode: JevConfig.Mode = JevConfig.Real, input: String = text) =
    new DocumentClassifier(client, config(mode)).classify(userId, "document test", input).futureValue

  private def classification(documentClass: DocumentClass) =
    DocumentClassification(documentClass, BigDecimal("0.95"), injectionSuspected = false, BigDecimal("0.99"))

  "classify" should {
    "map each type key to its document class" in {
      val expected = Map(
        "bank_statement" -> DocumentClass.BankStatement,
        "purchase_ticket" -> DocumentClass.PurchaseTicket,
        "bank_voucher" -> DocumentClass.BankVoucher,
        "invoice" -> DocumentClass.Invoice,
        "other" -> DocumentClass.Other
      )
      expected.foreach { case (key, documentClass) =>
        classify(stub(key)) must be(Classified(classification(documentClass)))
      }
    }

    "flag a suspected injection with its confidence" in {
      val result = classify(
        stub("purchase_ticket", injectionChoice = "injection_attempt", injectionConfidence = BigDecimal("0.7"))
      )
      result must be(
        Classified(
          DocumentClassification(
            DocumentClass.PurchaseTicket,
            BigDecimal("0.95"),
            injectionSuspected = true,
            BigDecimal("0.7")
          )
        )
      )
    }

    "send the type and injection questions with their criteria" in {
      val client = stub("purchase_ticket")
      classify(client)
      client.calls.map(_.questionName).toList must be(List("document_type", "prompt_injection"))
      client.calls.map(_.state).toList must be(List(text, text))

      val typeCriteria = client.calls.head.criteria
      typeCriteria must be(
        Map(
          "bank_statement" -> "a bank or credit-card ACCOUNT STATEMENT listing many transactions over a period",
          "purchase_ticket" -> "an itemized store PURCHASE TICKET/RECEIPT: products with prices from a shop",
          "bank_voucher" -> "a card-payment voucher/slip: amount, bank, card, authorization/TPV, NO items",
          "invoice" -> "a factura/invoice/bill (fiscal/billing details, often says FACTURA)",
          "other" -> "none of these / unreadable"
        )
      )

      val injection = client.calls(1)
      injection.criteria.keySet must be(Set("clean", "injection_attempt"))
      injection.instructions must include("instructs")
      injection.instructions must include("reveal its prompt or secrets")
      injection.instructions must include("exfiltrate data")
    }

    "cap the text sent to JEV at 8000 chars" in {
      val client = stub("bank_statement")
      classify(client, input = "x" * 9000)
      client.calls.map(_.state.length).toList must be(List(8000, 8000))
    }

    "be inconclusive without calling JEV in mock mode" in {
      val client = stub("bank_statement")
      classify(client, mode = JevConfig.Mock) must be(Inconclusive("jev.mode is mock", observed = None))
      client.calls must be(empty)
    }

    "be inconclusive when JEV fails, without the error message in the reason" in {
      val failures = List(
        new RuntimeException("JEV request failed, status 500: document text echoed back"),
        new JevRateLimitedException("JEV is rate limiting us, status 429 after 2 retries: document text echoed back")
      )
      failures.foreach { failure =>
        val result = classify(new StubJevClient(_ => Future.failed(failure)))
        result must be(Inconclusive(s"JEV failed: ${failure.getClass.getSimpleName}", observed = None))
      }
    }

    "be inconclusive when the client throws instead of failing the future" in {
      val client = new StubJevClient(_ => throw new RuntimeException("boom"))
      classify(client) mustBe an[Inconclusive]
    }

    "be inconclusive when JEV answers an unknown type key" in {
      classify(stub("passport")) mustBe an[Inconclusive]
    }

    "be inconclusive when the injection question fails" in {
      val client = new StubJevClient({
        case "document_type" => Future.successful(JevChoice("invoice", BigDecimal("0.95"), Map.empty))
        case _ => Future.failed(new RuntimeException("JEV request failed, status 500"))
      })
      classify(client) must be(Inconclusive("JEV failed: RuntimeException", observed = None))
    }

    "be inconclusive with the observed answer when the type confidence is below the threshold" in {
      val result = classify(stub("invoice", typeConfidence = BigDecimal("0.84")))
      val observed =
        DocumentClassification(
          DocumentClass.Invoice,
          BigDecimal("0.84"),
          injectionSuspected = false,
          BigDecimal("0.99")
        )
      result must be(Inconclusive("type confidence 0.84 is below 0.85", observed = Some(observed)))
      result.toPersist must be(Some(observed))
    }

    "classify when the type confidence equals the threshold" in {
      classify(stub("invoice", typeConfidence = BigDecimal("0.85"))) mustBe a[Classified]
    }
  }

  "rejection" should {
    val accepted = Set[DocumentClass](DocumentClass.PurchaseTicket)

    "reject a confident class that is not accepted" in {
      Classified(classification(DocumentClass.BankVoucher)).rejection(accepted) must be(
        Some(DocumentClass.BankVoucher)
      )
    }

    "not reject an accepted class" in {
      Classified(classification(DocumentClass.PurchaseTicket)).rejection(accepted) must be(None)
    }

    "never reject an inconclusive result" in {
      Inconclusive("low confidence", Some(classification(DocumentClass.Invoice))).rejection(accepted) must be(None)
      Inconclusive("jev.mode is mock", None).rejection(accepted) must be(None)
    }
  }

  "toPersist" should {
    "return the classification of a classified result" in {
      Classified(classification(DocumentClass.Invoice)).toPersist must be(Some(classification(DocumentClass.Invoice)))
    }

    "return nothing for an inconclusive result without an observed answer" in {
      Inconclusive("JEV failed: RuntimeException", observed = None).toPersist must be(None)
      Inconclusive("jev.mode is mock", observed = None).toPersist must be(None)
    }
  }
}
