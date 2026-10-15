package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.{useHistory, useParams}
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/** Review one draft import (reached from the bank statements page): fix the AI-extracted metadata/rows and confirm it
  * into a real statement once it reconciles, or discard it.
  */
object BankStatementTemplatePage {

  private val txnTypes = List("purchase", "payment", "fee", "interest", "refund")

  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx, user))

  case class Props(ctx: AppContext, user: User)

  private val hcellSx = new CSSPropertiesUtils {
    fontWeight = "600"; fontSize = "12px"; color = "var(--lc-text-2)"
  }

  def amountColor(amt: BigDecimal): String =
    if (amt >= 0) "var(--lc-brand)" else "var(--lc-error)"

  def metaComplete(m: BankStatementDraft.Metadata): Boolean =
    m.bankName.exists(_.trim.nonEmpty) && m.productType.exists(_.trim.nonEmpty) &&
      m.currency.exists(_.trim.nonEmpty) && m.accountLast4.exists(_.trim.nonEmpty) &&
      m.periodStart.isDefined && m.periodEnd.isDefined

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val client = props.ctx.api.client
    val history = useHistory()
    val params = useParams()
    val draftIdOpt = Try(UUID.fromString(params.get("draftId").getOrElse(""))).toOption

    val (loading, setLoading) = Hooks.useState(true)
    val (payloadOpt, setPayloadOpt) = Hooks.useState[Option[BankStatementDraft.Payload]](None)
    val (filename, setFilename) = Hooks.useState("")
    val (bankNames, setBankNames) = Hooks.useState[List[String]](Nil)
    val (error, setError) = Hooks.useState("")
    val (busy, setBusy) = Hooks.useState(false)

    Hooks.useEffect(
      () => {
        draftIdOpt match {
          case None => setError("Invalid draft"); setLoading(false)
          case Some(id) =>
            val load = for {
              draft <- client.getBankStatementDraft(id)
              stmts <- client.getBankStatements
            } yield (draft, stmts.data.map(_.bankName).distinct.sorted)
            load.onComplete {
              case Success((draft, banks)) =>
                setFilename(draft.filename); setPayloadOpt(draft.payload); setBankNames(banks); setLoading(false)
              case Failure(ex) => setError(ex.getMessage); setLoading(false)
            }
        }
      },
      js.Array(draftIdOpt.map(_.toString).getOrElse(""))
    )

    def setMeta(f: BankStatementDraft.Metadata => BankStatementDraft.Metadata): Unit =
      setPayloadOpt(_.map(p => p.copy(metadata = f(p.metadata))))

    def setTxn(idx: Int)(f: BankStatementDraft.Transaction => BankStatementDraft.Transaction): Unit =
      setPayloadOpt(
        _.map(p =>
          p.copy(transactions = p.transactions.zipWithIndex.map { case (t, i) =>
            if (i == idx) f(t) else t
          })
        )
      )

    def removeTxn(idx: Int): Unit =
      setPayloadOpt(
        _.map(p =>
          p.copy(transactions = p.transactions.zipWithIndex.collect {
            case (t, i) if i != idx => t
          })
        )
      )

    def doSave(): Unit = (draftIdOpt, payloadOpt) match {
      case (Some(id), Some(p)) =>
        setBusy(true); setError("")
        client.updateBankStatementDraft(id, BankStatementDraft.Update.Request(p)).onComplete {
          case Success(data) => data.payload.foreach(pl => setPayloadOpt(Some(pl))); setBusy(false)
          case Failure(ex) => setError(ex.getMessage); setBusy(false)
        }
      case _ => ()
    }

    def doConfirm(): Unit = (draftIdOpt, payloadOpt) match {
      case (Some(id), Some(p)) =>
        setBusy(true); setError("")
        val run = for {
          _ <- client.updateBankStatementDraft(id, BankStatementDraft.Update.Request(p))
          confirmed <- client.confirmBankStatementDraft(id)
        } yield confirmed
        run.onComplete {
          case Success(resp) => history.push(s"/bank-statements/${resp.bankStatementId}")
          case Failure(ex) => setError(ex.getMessage); setBusy(false)
        }
      case _ => ()
    }

    def doDiscard(): Unit = draftIdOpt.foreach { id =>
      setBusy(true); setError("")
      client.discardBankStatementDraft(id).onComplete {
        case Success(_) => history.push("/bank-statements")
        case Failure(ex) => setError(ex.getMessage); setBusy(false)
      }
    }

    def textField(
        label: String,
        value: String,
        onValue: String => Unit,
        multilineField: Boolean = false
    ): ReactElement =
      mui.TextField
        .outlined()
        .label(label)
        .size("small")
        .multiline(multilineField)
        .value(value)
        .disabled(busy)
        .onChange(e => onValue(e.target.asInstanceOf[dom.HTMLInputElement].value))
        .sx(new CSSPropertiesUtils { minWidth = "150px" })

    def selectField(label: String, value: String, options: List[String], onValue: String => Unit): ReactElement = {
      val items = ("" :: options).map { v =>
        mui.MenuItem.normal()(if (v.isEmpty) "—" else v).value(v).withKey(if (v.isEmpty) "none" else v).build
      }
      mui.TextField
        .outlined()
        .select(true)
        .label(label)
        .size("small")
        .value(value)
        .disabled(busy)
        .onChange(e => onValue(e.target.asInstanceOf[dom.HTMLInputElement].value))
        .sx(new CSSPropertiesUtils { minWidth = "150px" })(items*)
    }

    def optStr(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)
    def optDate(s: String): Option[LocalDate] = optStr(s).flatMap(v => Try(LocalDate.parse(v)).toOption)
    def optAmount(s: String): Option[BigDecimal] = optStr(s).flatMap(v => Try(BigDecimal(v)).toOption)

    def metadataForm(m: BankStatementDraft.Metadata): ReactElement =
      Fragment(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexWrap = "wrap"; gap = "12px"; marginBottom = "8px" })(
          textField("Bank name", m.bankName.getOrElse(""), v => setMeta(_.copy(bankName = optStr(v)))),
          selectField(
            "Type",
            m.productType.getOrElse(""),
            List("credit", "debit"),
            v => setMeta(_.copy(productType = optStr(v)))
          ),
          textField("Currency", m.currency.getOrElse(""), v => setMeta(_.copy(currency = optStr(v)))),
          textField("Account last 4", m.accountLast4.getOrElse(""), v => setMeta(_.copy(accountLast4 = optStr(v)))),
          textField(
            "Period start (YYYY-MM-DD)",
            m.periodStart.map(_.toString).getOrElse(""),
            v => setMeta(_.copy(periodStart = optDate(v)))
          ),
          textField(
            "Period end (YYYY-MM-DD)",
            m.periodEnd.map(_.toString).getOrElse(""),
            v => setMeta(_.copy(periodEnd = optDate(v)))
          )
        ),
        if (bankNames.nonEmpty)
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; alignItems = "center"; flexWrap = "wrap"; gap = "6px"; marginBottom = "12px"
          })(
            Fragment(
              (mui
                .Typography("Your banks:")
                .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }): ReactElement) ::
                bankNames.map { b =>
                  (mui.Button
                    .normal()(b)
                    .size("small")
                    .variant("outlined")
                    .disabled(busy)
                    .onClick(_ => setMeta(_.copy(bankName = Some(b))))
                    .withKey(b)
                    .build: ReactElement)
                }*
            )
          )
        else Fragment()
      )

    def transactionsTable(transactions: List[BankStatementDraft.Transaction]): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-border)"; borderRadius = "8px"; overflow = "hidden"; marginBottom = "16px";
        overflowX = "auto"
      })(
        mui.Table.size("small")(
          mui.TableHead(
            mui.TableRow(
              mui.TableCell.sx(hcellSx)("Date"),
              mui.TableCell.align("right").sx(hcellSx)("Amount"),
              mui.TableCell.sx(hcellSx)("Type"),
              mui.TableCell.sx(hcellSx)("Description"),
              mui.TableCell.sx(hcellSx)("")
            )
          ),
          mui.TableBody(
            Fragment(
              transactions.zipWithIndex.map { case (t, idx) =>
                (mui
                  .TableRow(
                    mui.TableCell
                      .sx(new CSSPropertiesUtils { fontSize = "12px"; whiteSpace = "nowrap"; minWidth = "120px" })(
                        textField("", t.date.toString, v => optDate(v).foreach(d => setTxn(idx)(_.copy(date = d))))
                      ),
                    mui.TableCell
                      .align("right")
                      .sx(new CSSPropertiesUtils {
                        fontSize = "12px"; color = amountColor(t.amount); minWidth = "110px"
                      })(
                        textField(
                          "",
                          t.amount.toString,
                          v => optAmount(v).foreach(a => setTxn(idx)(_.copy(amount = a)))
                        )
                      ),
                    mui.TableCell(
                      selectField("", t.txnType.getOrElse(""), txnTypes, v => setTxn(idx)(_.copy(txnType = optStr(v))))
                    ),
                    mui.TableCell(
                      textField("", t.description, v => setTxn(idx)(_.copy(description = v)), multilineField = true)
                    ),
                    mui.TableCell(
                      mui.Button
                        .normal()("Remove")
                        .size("small")
                        .variant("outlined")
                        .disabled(busy)
                        .onClick(_ => removeTxn(idx))
                    )
                  )
                  .withKey(idx.toString): ReactElement)
              }*
            )
          )
        )
      )

    def editingView(payload: BankStatementDraft.Payload): ReactElement = {
      val reconciled = payload.reconciled
      val canConfirm = reconciled && metaComplete(payload.metadata) && !busy
      Fragment(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "8px"
        })(
          mui.Button
            .normal()("← Back")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push("/bank-statements")),
          mui.Typography(filename).sx(new CSSPropertiesUtils { fontSize = "16px"; fontWeight = "700" }),
          mui
            .Typography(
              if (reconciled) s"✓ Reconciled — ${payload.transactions.size} transactions"
              else s"⚠ Not reconciled — ${payload.discrepancy.getOrElse("")}"
            )
            .sx(new CSSPropertiesUtils {
              fontSize = "13px"; fontWeight = "600"
              color = if (reconciled) "var(--lc-brand)" else "var(--lc-error)"
            })
        ),
        metadataForm(payload.metadata),
        transactionsTable(payload.transactions),
        if (!metaComplete(payload.metadata))
          mui
            .Typography("Fill in every metadata field (bank, type, currency, account, period) before confirming.")
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)"; marginBottom = "8px" })
        else Fragment(),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
          mui.Button
            .normal()(if (busy) "Saving…" else "Save changes")
            .size("small")
            .variant("outlined")
            .disabled(busy)
            .onClick(_ => doSave()),
          mui.Button
            .normal()("Confirm import")
            .size("small")
            .variant("contained")
            .disabled(!canConfirm)
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
            .onClick(_ => doConfirm()),
          mui.Button
            .normal()("Discard")
            .size("small")
            .variant("outlined")
            .disabled(busy)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; borderColor = "var(--lc-error)" })
            .onClick(_ => doDiscard())
        )
      )
    }

    val content: ReactElement =
      if (loading)
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "10px" })(
          mui.CircularProgress().size(20.0),
          mui.Typography("Loading…").sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" })
        )
      else
        payloadOpt match {
          case Some(p) => editingView(p)
          case None =>
            mui
              .Typography("This draft isn't ready yet, or no longer exists.")
              .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" })
        }

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "1000px" })(
      mui.Box.sx(new CSSPropertiesUtils { marginBottom = "20px" })(
        mui
          .Typography("Review Statement Import")
          .sx(new CSSPropertiesUtils { fontSize = "20px"; fontWeight = "700"; color = "var(--lc-text)" })
      ),
      content,
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginTop = "12px" })
      else Fragment()
    )
  }
}
