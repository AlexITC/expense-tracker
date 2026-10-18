package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{
  AiSuggestions,
  AiSuggestionsStatus,
  ConfirmBankStatementRow,
  CreateExpenseFromRow,
  GetBankStatement,
  IgnoreRow,
  UnignoreRow
}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetImportRules, GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.{useHistory, useParams}
import org.scalajs.dom
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object BankStatementReviewPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  type RowEdits = Map[UUID, (String, String, String)]

  /** A one-line summary of the statement metadata. */
  def statementMetadataLine(stmt: GetBankStatement.Response): ReactElement = {
    val productType = stmt.productType match {
      case "credit" => "Type: Credit card"
      case "debit" => "Type: Debit account"
      case other => s"Type: $other"
    }
    val parts = List(
      s"Bank: ${stmt.bankName}",
      productType,
      s"Currency: ${stmt.currency}",
      s"Account: ••••${stmt.accountLast4}",
      s"Period: ${stmt.periodStart} – ${stmt.periodEnd}"
    )
    mui
      .Typography(parts.mkString("    ·    "))
      .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)"; marginBottom = "8px" })
  }

  def resultLabel(
      rowId: UUID,
      descById: Map[UUID, String],
      outcome: Either[String, Unit]
  ): String = {
    val desc = descById.getOrElse(rowId, rowId.toString.take(8))
    outcome match {
      case Right(_) => s"✓ $desc"
      case Left(err) => s"✗ $desc: $err"
    }
  }

  /** AI suggestions are still being computed: not written yet (None) or explicitly Pending. */
  def suggestionsPending(ai: Option[AiSuggestions]): Boolean =
    ai.forall(_.status == AiSuggestionsStatus.Pending)

  // Statements confirmed before AI suggestions existed stay NULL forever, so polling is capped.
  private val SuggestionsPollMaxAttempts = 20
  private val SuggestionsPollIntervalMs = 3000

  private val hintStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-brand)" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()
    val location = RouterCompat.useTypedLocation()
    val params = useParams()
    val stmtIdOpt = Try(UUID.fromString(params.get("stmtId").getOrElse(""))).toOption

    val (statement, setStatement) = Hooks.useState[Option[GetBankStatement.Response]](None)
    val (statementRows, setStatementRows) = Hooks.useState[List[GetBankStatement.StatementRowData]](Nil)
    val (rowEdits, setRowEdits) = Hooks.useState[RowEdits](Map.empty)
    val (error, setError) = Hooks.useState("")
    val (toast, setToast) = Hooks.useState[Option[String]](None)
    val (loading, setLoading) = Hooks.useState(true)

    val (merchantList, setMerchantList) = Hooks.useState[List[GetMerchants.Response.Merchant]](Nil)
    val (categoryList, setCategoryList) = Hooks.useState[List[GetCategories.Response.Category]](Nil)
    val (ruleList, setRuleList) = Hooks.useState[List[ImportRuleMatcher.Rule]](Nil)

    val initialTab = {
      val params = new URLSearchParams(location.search)
      val t = params.get("tab")
      if (t == null || t.isEmpty) "ready" else t
    }
    val (rowFilter, setRowFilter) = Hooks.useState(initialTab)
    val (selectedRowIds, setSelectedRowIds) = Hooks.useState[Set[UUID]](Set.empty)

    val (bulkIgnoreProgress, setBulkIgnoreProgress) = Hooks.useState[Option[(Int, Int, Int)]](None)
    val (bulkIgnoreResults, setBulkIgnoreResults) = Hooks.useState[List[(UUID, Either[String, Unit])]](Nil)
    val (bulkImportProgress, setBulkImportProgress) = Hooks.useState[Option[(Int, Int, Int)]](None)
    val (bulkImportResults, setBulkImportResults) = Hooks.useState[List[(UUID, Either[String, Unit])]](Nil)

    val (creatingForRow, setCreatingForRow) = Hooks.useState[Option[UUID]](None)
    val (linkingForRow, setLinkingForRow) = Hooks.useState[Option[UUID]](None)
    val (creatingRefundForRow, setCreatingRefundForRow) = Hooks.useState[Option[UUID]](None)
    val (ruleFormForRow, setRuleFormForRow) = Hooks.useState[Option[UUID]](None)

    val (menuAnchorPos, setMenuAnchorPos) = Hooks.useState[Option[(Double, Double)]](None)
    val (menuForRow, setMenuForRow) = Hooks.useState[Option[UUID]](None)

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    // Load statement and supporting data on mount
    Hooks.useEffect(
      () => {
        stmtIdOpt match {
          case None => setLoading(false); setError("Invalid statement ID")
          case Some(stmtId) =>
            props.ctx.api.client.getBankStatement(stmtId).onComplete {
              case Success(full) =>
                setStatement(Some(full))
                setStatementRows(full.statementRows)
                setRowEdits(
                  full.statementRows.map(r => r.id -> (r.date.toString, r.amount.toString, r.description)).toMap
                )
                setLoading(false)
              case Failure(ex) =>
                setError(ex.getMessage)
                setLoading(false)
            }
            props.ctx.api.client.getMerchants.onComplete {
              case Success(resp) => setMerchantList(resp.data)
              case Failure(_) => ()
            }
            props.ctx.api.client.getCategories.onComplete {
              case Success(resp) => setCategoryList(resp.data)
              case Failure(_) => ()
            }
            props.ctx.api.client.getImportRules.onComplete {
              case Success(resp) =>
                setRuleList(resp.data.map(r => ImportRuleMatcher.Rule(r.id, r.action, r.pattern, r.merchantId)))
              case Failure(_) => ()
            }
        }
        ()
      },
      js.Array(stmtIdOpt.map(_.toString).getOrElse(""))
    )

    // Poll for AI suggestions until they are resolved or the attempts cap is reached
    val (suggestionsPollAttempts, setSuggestionsPollAttempts) = Hooks.useState(0)
    val pollingSuggestions =
      statement.exists(s => suggestionsPending(s.aiSuggestions)) &&
        suggestionsPollAttempts < SuggestionsPollMaxAttempts
    val suggestionsPollKey = s"${stmtIdOpt.map(_.toString).getOrElse("")}:$pollingSuggestions"
    Hooks.useEffect(
      () => {
        (stmtIdOpt, pollingSuggestions) match {
          case (Some(stmtId), true) =>
            val timer = dom.window.setInterval(
              () => {
                setSuggestionsPollAttempts(_ + 1)
                props.ctx.api.client.getBankStatement(stmtId).onComplete {
                  case Success(full) => setStatement(cur => cur.map(_.copy(aiSuggestions = full.aiSuggestions)))
                  case Failure(_) => ()
                }
              },
              SuggestionsPollIntervalMs
            )
            () => { dom.window.clearInterval(timer); () }
          case _ => () => ()
        }
      },
      js.Array(suggestionsPollKey)
    )

    def isReadyToProcess(row: GetBankStatement.StatementRowData): Boolean =
      row.expenseId.isEmpty && row.refundId.isEmpty && row.ignoredAt.isEmpty && row.amount < 0 && {
        ImportRuleMatcher.findRowWinner(ruleList, row.description) match {
          case None => false
          case Some(w) if w.action == ImportRuleAction.Ignore => true
          case Some(w) => w.merchantId.exists(mid => merchantList.find(_.id == mid).exists(_.categoryId.isDefined))
        }
      }

    def filteredRows: List[GetBankStatement.StatementRowData] = rowFilter match {
      case "ignored" => statementRows.filter(_.ignoredAt.isDefined)
      case "linked" => statementRows.filter(r => r.expenseId.isDefined || r.refundId.isDefined)
      case "ready" => statementRows.filter(isReadyToProcess)
      case "all" => statementRows
      case _ => statementRows.filter(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
    }

    def handleIgnore(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setSelectedRowIds(cur => cur - rowId)
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r))
          showToast("Row ignored")
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnignore(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unignoreRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnlink(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unlinkRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(expenseId = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnlinkRefund(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unlinkRowRefund(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(refundId = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleBulkIgnore(stmtId: UUID, ids: List[UUID]): Unit = {
      val total = ids.length
      setBulkIgnoreProgress(Some((0, total, 0)))
      setBulkIgnoreResults(Nil)
      def loop(remaining: List[UUID], done: Int, failed: Int): Unit =
        remaining match {
          case Nil => setBulkIgnoreProgress(None); setSelectedRowIds(Set.empty)
          case rowId :: rest =>
            props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
              case Success(_) =>
                setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r))
                setBulkIgnoreResults(cur => cur :+ (rowId -> Right(())))
                setBulkIgnoreProgress(Some((done + 1, total, failed)))
                loop(rest, done + 1, failed)
              case Failure(ex) =>
                setBulkIgnoreResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                setBulkIgnoreProgress(Some((done + 1, total, failed + 1)))
                loop(rest, done + 1, failed + 1)
            }
        }
      loop(ids, 0, 0)
    }

    def handleBulkImport(stmtId: UUID, ids: List[UUID]): Unit = {
      val total = ids.length
      setBulkImportProgress(Some((0, total, 0)))
      setBulkImportResults(Nil)
      def loop(remaining: List[UUID], done: Int, failed: Int): Unit =
        remaining match {
          case Nil =>
            setBulkImportProgress(None)
            setSelectedRowIds(Set.empty)
            if (failed == 0) showToast(s"Imported $done expenses")
          case rowId :: rest =>
            statementRows.find(_.id == rowId) match {
              case None =>
                setBulkImportResults(cur => cur :+ (rowId -> Left("Row not found")))
                loop(rest, done + 1, failed + 1)
              case Some(row) =>
                ImportRuleMatcher.findRowWinner(ruleList, row.description) match {
                  case Some(w) if w.action == ImportRuleAction.Ignore =>
                    props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
                      case Success(_) =>
                        setStatementRows(cur =>
                          cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r)
                        )
                        setBulkImportResults(cur => cur :+ (rowId -> Right(())))
                        setBulkImportProgress(Some((done + 1, total, failed)))
                        loop(rest, done + 1, failed)
                      case Failure(ex) =>
                        setBulkImportResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                        setBulkImportProgress(Some((done + 1, total, failed + 1)))
                        loop(rest, done + 1, failed + 1)
                    }
                  case winner =>
                    val merchantId = winner.filter(_.action == ImportRuleAction.SetMerchant).flatMap(_.merchantId)
                    val catIdOpt = merchantId.flatMap(mid => merchantList.find(_.id == mid).flatMap(_.categoryId))
                    catIdOpt match {
                      case None =>
                        setBulkImportResults(cur => cur :+ (rowId -> Left("No default category")))
                        loop(rest, done + 1, failed + 1)
                      case Some(catId) =>
                        val req = CreateExpenseFromRow.Request(
                          amount = row.amount.abs,
                          date = row.date,
                          time = None,
                          merchantId = merchantId,
                          notes = Some(row.description).filter(_.nonEmpty),
                          splits = List(CreateExpenseFromRow.Split(catId, row.amount.abs)),
                          receiptId = None
                        )
                        props.ctx.api.client.createExpenseFromRow(stmtId, rowId, req).onComplete {
                          case Success(resp) =>
                            setStatementRows(cur =>
                              cur.map(r => if (r.id == rowId) r.copy(expenseId = Some(resp.expenseId)) else r)
                            )
                            setBulkImportResults(cur => cur :+ (rowId -> Right(())))
                            setBulkImportProgress(Some((done + 1, total, failed)))
                            loop(rest, done + 1, failed)
                          case Failure(ex) =>
                            setBulkImportResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                            setBulkImportProgress(Some((done + 1, total, failed + 1)))
                            loop(rest, done + 1, failed + 1)
                        }
                    }
                }
            }
        }
      loop(ids, 0, 0)
    }

    val drawerOpen =
      creatingForRow.isDefined || creatingRefundForRow.isDefined ||
        linkingForRow.isDefined || ruleFormForRow.isDefined
    val drawerRowId =
      creatingForRow.orElse(creatingRefundForRow).orElse(linkingForRow).orElse(ruleFormForRow)

    def closeDrawer(): Unit = {
      setCreatingForRow(None); setCreatingRefundForRow(None)
      setLinkingForRow(None); setRuleFormForRow(None)
    }

    if (loading)
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(mui.CircularProgress())
    else if (error.nonEmpty && statement.isEmpty)
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(
        mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      )
    else
      statement match {
        case None =>
          mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(
            mui.Typography("Statement not found").sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
          )
        case Some(stmt) =>
          val tableRows = filteredRows
          val selectedInView = selectedRowIds.intersect(tableRows.map(_.id).toSet)
          val bulkActive = selectedInView.nonEmpty && bulkIgnoreProgress.isEmpty && bulkImportProgress.isEmpty
          val allTableSelected = tableRows.nonEmpty && tableRows.forall(r => selectedRowIds.contains(r.id))
          val someTableSelected = tableRows.exists(r => selectedRowIds.contains(r.id)) && !allTableSelected

          val countReady = statementRows.count(isReadyToProcess)
          val countUnprocessed =
            statementRows.count(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
          val countIgnored = statementRows.count(_.ignoredAt.isDefined)
          val countLinked = statementRows.count(r => r.expenseId.isDefined || r.refundId.isDefined)

          val hcell = new CSSPropertiesUtils {
            fontWeight = "700"; fontSize = "11px"; letterSpacing = "0.06em"
            textTransform = "uppercase"; color = "var(--lc-text-2)"
          }

          def rowPrimaryAction(row: GetBankStatement.StatementRowData): ReactElement =
            if (row.expenseId.isDefined || row.refundId.isDefined)
              (mui.Button
                .normal()("Linked")
                .size("small")
                .disabled(true)
                .sx(new CSSPropertiesUtils { color = "var(--lc-brand)" })
                .build: ReactElement)
            else if (row.ignoredAt.isDefined)
              (mui.Button
                .normal()("Un-ignore")
                .size("small")
                .onClick(_ => handleUnignore(stmt.id, row.id))
                .build: ReactElement)
            else if (isReadyToProcess(row))
              ImportRuleMatcher.findRowWinner(ruleList, row.description) match {
                case Some(w) if w.action == ImportRuleAction.Ignore =>
                  (mui.Button
                    .normal()("Ignore")
                    .size("small")
                    .variant("contained")
                    .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                    .onClick(_ => handleIgnore(stmt.id, row.id))
                    .build: ReactElement)
                case _ =>
                  (mui.Button
                    .normal()(if (row.amount > 0) "Import refund" else "Confirm")
                    .size("small")
                    .variant("contained")
                    .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                    .onClick(_ => handleBulkImport(stmt.id, List(row.id)))
                    .build: ReactElement)
              }
            else if (row.amount > 0)
              (mui.Button
                .normal()("Create refund")
                .size("small")
                .variant("outlined")
                .onClick(_ => setCreatingRefundForRow(Some(row.id)))
                .build: ReactElement)
            else
              (mui.Button
                .normal()("Create expense")
                .size("small")
                .variant("outlined")
                .onClick(_ => { setCreatingForRow(Some(row.id)); setLinkingForRow(None) })
                .build: ReactElement)

          def matchCell(row: GetBankStatement.StatementRowData): ReactElement =
            ImportRuleMatcher.findRowWinner(ruleList, row.description) match {
              case Some(w) =>
                val lbl =
                  if (w.action == ImportRuleAction.Ignore) "→ Ignore"
                  else w.merchantId.flatMap(mid => merchantList.find(_.id == mid)).map(_.name).getOrElse("")
                (mui.Chip
                  .label(lbl)
                  .size("small")
                  .sx(new CSSPropertiesUtils {
                    height = "22px"; fontSize = "12px"; backgroundColor = "var(--lc-tint)";
                    color = "var(--lc-brand-dark)"
                  }): ReactElement)
              case None =>
                if (row.expenseId.isDefined || row.refundId.isDefined)
                  (mui.Chip
                    .label("Linked")
                    .size("small")
                    .sx(new CSSPropertiesUtils {
                      height = "22px"; fontSize = "12px"; color = "var(--lc-brand)"; backgroundColor = "var(--lc-tint)"
                    }): ReactElement)
                else if (pollingSuggestions && row.ignoredAt.isEmpty && row.amount < 0)
                  mui
                    .Typography("suggesting…")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
                else
                  mui.Typography("—").sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
            }

          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; flexDirection = "column"; height = "100%"; minHeight = "0"
          })(
            // ── Header + Tabs ──
            mui.Box.sx(new CSSPropertiesUtils { padding = "20px 24px 0 24px" })(
              mui.Box.sx(new CSSPropertiesUtils {
                display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "4px"
              })(
                mui
                  .Typography(stmt.documentFilename)
                  .sx(new CSSPropertiesUtils {
                    fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)"
                    fontFamily = "\"IBM Plex Sans\", sans-serif"
                  }),
                mui
                  .Typography(s"${statementRows.size} transactions")
                  .sx(new CSSPropertiesUtils { fontSize = "14px"; color = "var(--lc-text-2)" }),
                mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
                mui.Button
                  .normal()("Auto-reconcile")
                  .size("small")
                  .variant("outlined")
                  .onClick(_ => history.push(s"/bank-statements/${stmt.id}/auto-reconcile")),
                mui.Button
                  .normal()("← Back")
                  .size("small")
                  .variant("outlined")
                  .onClick(_ => history.push("/bank-statements"))
              ),
              statementMetadataLine(stmt),
              mui.Tabs
                .value(rowFilter)
                .onChange((_, v) => {
                  val tab = v.asInstanceOf[String]
                  setRowFilter(tab)
                  setSelectedRowIds(Set.empty)
                  val pathname = location.pathname
                  history.replace(s"$pathname?tab=$tab")
                })
                .sx(new CSSPropertiesUtils { marginTop = "8px"; minHeight = "42px" })(
                  mui.Tab.normal().value("ready").label(s"Ready ($countReady)").build,
                  mui.Tab.normal().value("unprocessed").label(s"Unprocessed ($countUnprocessed)").build,
                  mui.Tab.normal().value("ignored").label(s"Ignored ($countIgnored)").build,
                  mui.Tab.normal().value("linked").label(s"Linked ($countLinked)").build,
                  mui.Tab.normal().value("all").label(s"All (${statementRows.size})").build
                )
            ),
            // ── Bulk bar ──
            if (selectedInView.nonEmpty)
              mui.Box.sx(new CSSPropertiesUtils {
                margin = "12px 24px 0 24px"; padding = "8px 16px"; borderRadius = "8px"
                display = "flex"; alignItems = "center"; gap = "12px"
                backgroundColor = "var(--lc-brand)"
              })(
                mui
                  .Typography(s"${selectedInView.size} selected")
                  .sx(new CSSPropertiesUtils { fontWeight = "600"; fontSize = "14px"; color = "var(--lc-brand-on)" }),
                mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
                bulkImportProgress match {
                  case Some((done, total, failed)) =>
                    mui
                      .Typography(s"Importing $done/$total${if (failed > 0) s" ($failed failed)" else ""}…")
                      .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
                  case None =>
                    (mui.Button
                      .normal()("Process ready")
                      .size("small")
                      .variant("contained")
                      .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-paper)"; color = "var(--lc-brand)" })
                      .disabled(!bulkActive)
                      .onClick(_ => handleBulkImport(stmt.id, selectedInView.toList))
                      .build: ReactElement)
                },
                bulkIgnoreProgress match {
                  case Some((done, total, failed)) =>
                    mui
                      .Typography(s"Ignoring $done/$total${if (failed > 0) s" ($failed failed)" else ""}…")
                      .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
                  case None =>
                    (mui.Button
                      .normal()("Ignore")
                      .size("small")
                      .variant("text")
                      .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)" })
                      .disabled(!bulkActive)
                      .onClick(_ => handleBulkIgnore(stmt.id, selectedInView.toList))
                      .build: ReactElement)
                },
                (mui.IconButton
                  .normal()(
                    mui.Typography("×").sx(new CSSPropertiesUtils { fontSize = "20px"; color = "var(--lc-brand-on)" })
                  )
                  .size("small")
                  .onClick(_ => setSelectedRowIds(Set.empty))
                  .build: ReactElement)
              )
            else Fragment(),
            // ── Error ──
            if (error.nonEmpty)
              mui.Box.sx(new CSSPropertiesUtils { padding = "8px 24px 0 24px" })(
                mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
              )
            else Fragment(),
            // ── Table ──
            mui.Box.sx(new CSSPropertiesUtils { flex = "1"; overflowY = "auto"; padding = "0 24px 24px 24px" })(
              mui.Table.size("small")(
                mui.TableHead(
                  mui.TableRow(
                    mui.TableCell.padding("checkbox")(
                      if (rowFilter == "unprocessed" || rowFilter == "ready")
                        (mui.Checkbox
                          .checked(allTableSelected)
                          .indeterminate(someTableSelected)
                          .size("small")
                          .onChange((_, checked) =>
                            if (checked)
                              setSelectedRowIds(tableRows.map(_.id).toSet)
                            else setSelectedRowIds(Set.empty)
                          ): ReactElement)
                      else Fragment()
                    ),
                    mui.TableCell.sx(hcell)("Date"),
                    mui.TableCell.align("right").sx(hcell)("Amount"),
                    mui.TableCell.sx(hcell)("Description"),
                    mui.TableCell.sx(hcell)("Match"),
                    mui.TableCell.align("right").sx(hcell)("")
                  )
                ),
                if (tableRows.isEmpty)
                  mui.TableBody(
                    mui.TableRow(
                      mui.TableCell.colSpan(6)(
                        mui
                          .Typography("No transactions in this view.")
                          .sx(new CSSPropertiesUtils {
                            padding = "32px 0"; textAlign = "center"; color = "var(--lc-text-2)"; display = "block"
                          })
                      )
                    )
                  )
                else
                  mui.TableBody(
                    Fragment(
                      tableRows.map { row =>
                        val amtSign = if (row.amount > 0) "+" else ""
                        val amtColor = if (row.amount > 0) "var(--lc-brand)" else "var(--lc-error)"
                        val showCb = rowFilter == "unprocessed" || rowFilter == "ready"
                        (mui.TableRow
                          .hover(true)
                          .selected(selectedRowIds.contains(row.id))(
                            mui.TableCell.padding("checkbox")(
                              if (showCb)
                                (mui.Checkbox
                                  .checked(selectedRowIds.contains(row.id))
                                  .size("small")
                                  .onChange((_, checked) =>
                                    if (checked)
                                      setSelectedRowIds(cur => cur + row.id)
                                    else setSelectedRowIds(cur => cur - row.id)
                                  ): ReactElement)
                              else Fragment()
                            ),
                            mui.TableCell.sx(new CSSPropertiesUtils {
                              fontFamily = "\"IBM Plex Mono\", monospace"
                              fontSize = "13px"; color = "var(--lc-text-2)"; whiteSpace = "nowrap"
                            })(row.date.toString),
                            mui.TableCell.align("right")(
                              mui
                                .Typography(s"$amtSign${row.amount}")
                                .sx(new CSSPropertiesUtils {
                                  fontWeight = "700"; fontSize = "13px"; color = amtColor
                                  fontFamily = "\"IBM Plex Mono\", monospace"
                                })
                            ),
                            mui.TableCell.sx(new CSSPropertiesUtils { maxWidth = "280px" })(
                              mui
                                .Typography(row.description)
                                .sx(new CSSPropertiesUtils {
                                  fontSize = "13px"; fontFamily = "\"IBM Plex Mono\", monospace"
                                  overflow = "hidden"; textOverflow = "ellipsis"; whiteSpace = "nowrap"
                                  display = "block"
                                }),
                              if (row.txnType == "purchase") Fragment(): ReactElement
                              else
                                mui.Chip
                                  .label(row.txnType)
                                  .size("small")
                                  .sx(new CSSPropertiesUtils {
                                    height = "18px"; fontSize = "11px"; marginTop = "2px";
                                    backgroundColor = "var(--lc-tint)"; color = "var(--lc-text-2)"
                                  })
                            ),
                            mui.TableCell(matchCell(row)),
                            mui.TableCell.align("right")(
                              mui.Box.sx(new CSSPropertiesUtils {
                                display = "flex"; gap = "4px"
                                justifyContent = "flex-end"; alignItems = "center"
                              })(
                                rowPrimaryAction(row),
                                (mui.IconButton
                                  .normal()(
                                    mui
                                      .Typography("…")
                                      .sx(new CSSPropertiesUtils { fontSize = "18px"; lineHeight = "1" })
                                  )
                                  .size("small")
                                  .onClick(e => {
                                    val rect = e.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect()
                                    setMenuAnchorPos(Some((rect.right, rect.bottom)))
                                    setMenuForRow(Some(row.id))
                                  })
                                  .build: ReactElement)
                              )
                            )
                          )
                          .withKey(row.id.toString): ReactElement)
                      }*
                    )
                  )
              ),
              if (bulkImportResults.nonEmpty || bulkIgnoreResults.nonEmpty)
                mui.Box.sx(new CSSPropertiesUtils { marginTop = "16px" })(
                  Fragment({
                    val descById = statementRows.map(r => r.id -> r.description).toMap
                    (bulkImportResults ++ bulkIgnoreResults).map { case (rowId, outcome) =>
                      val lbl = resultLabel(rowId, descById, outcome)
                      val txtColor = outcome match {
                        case Right(_) => "var(--lc-success-text)"
                        case Left(_) => "var(--lc-error)"
                      }
                      (mui
                        .Typography(lbl)
                        .sx(new CSSPropertiesUtils { fontSize = "12px"; color = txtColor })
                        .withKey(rowId.toString): ReactElement)
                    }
                  }*)
                )
              else Fragment()
            ),
            // ── Row overflow menu (fixed-position custom dropdown) ──
            menuAnchorPos match {
              case Some((ax, ay)) if menuForRow.isDefined =>
                def closeMenu(): Unit = { setMenuAnchorPos(None); setMenuForRow(None) }
                val menuItemSx = new CSSPropertiesUtils {
                  padding = "10px 16px"; cursor = "pointer"; fontSize = "14px"
                }
                Fragment(
                  (mui.Box
                    .sx(new CSSPropertiesUtils {
                      position = "fixed"; top = "0"; left = "0"; right = "0"; bottom = "0"; zIndex = "1200"
                    })
                    .onClick(_ => closeMenu())(): ReactElement),
                  (mui.Box.sx(new CSSPropertiesUtils {
                    position = "fixed"
                    right = s"${org.scalajs.dom.window.innerWidth - ax}px"
                    top = s"${ay}px"
                    backgroundColor = "var(--lc-paper)"; border = "1px solid var(--lc-border)"
                    borderRadius = "6px"; boxShadow = "0 4px 16px rgba(0,0,0,0.12)"
                    zIndex = "1300"; minWidth = "200px"; overflow = "hidden"
                  })(
                    mui.Box
                      .sx(menuItemSx)
                      .onClick(_ =>
                        menuForRow.foreach { rowId =>
                          setLinkingForRow(Some(rowId)); setCreatingForRow(None); closeMenu()
                        }
                      )(
                        mui.Typography("Link to existing expense").sx(new CSSPropertiesUtils { fontSize = "14px" })
                      ),
                    mui.Divider(),
                    mui.Box
                      .sx(menuItemSx)
                      .onClick(_ =>
                        menuForRow.foreach { rowId =>
                          setRuleFormForRow(Some(rowId)); closeMenu()
                        }
                      )(
                        mui.Typography("Create import rule").sx(new CSSPropertiesUtils { fontSize = "14px" })
                      ),
                    mui.Divider(),
                    mui.Box
                      .sx(menuItemSx)
                      .onClick(_ =>
                        menuForRow.foreach { rowId =>
                          handleIgnore(stmt.id, rowId); closeMenu()
                        }
                      )(
                        mui.Typography("Ignore").sx(new CSSPropertiesUtils { fontSize = "14px" })
                      )
                  ): ReactElement)
                )
              case _ => Fragment()
            },
            // ── Detail Drawer ──
            mui.Drawer
              .anchor("right")
              .open(drawerOpen)
              .onClose((_, _) => closeDrawer())(
                mui.Box.sx(new CSSPropertiesUtils {
                  width = "480px"; maxWidth = "92vw"; height = "100%"
                  display = "flex"; flexDirection = "column"; padding = "20px"
                })(
                  mui.Box.sx(new CSSPropertiesUtils {
                    display = "flex"; alignItems = "center"; marginBottom = "16px"
                  })(
                    mui
                      .Typography("Review transaction")
                      .sx(new CSSPropertiesUtils { fontSize = "16px"; fontWeight = "600"; color = "var(--lc-text)" }),
                    mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
                    (mui.IconButton
                      .normal()(
                        mui.Typography("×").sx(new CSSPropertiesUtils { fontSize = "22px" })
                      )
                      .onClick(_ => closeDrawer())
                      .build: ReactElement)
                  ),
                  drawerRowId
                    .flatMap(id => statementRows.find(_.id == id))
                    .map { row =>
                      (Fragment(
                        mui.Box.sx(new CSSPropertiesUtils {
                          padding = "12px 16px"; marginBottom = "16px"; borderRadius = "8px"
                          border = "1px solid var(--lc-border)"; backgroundColor = "var(--lc-bg)"
                        })(
                          mui.Box.sx(new CSSPropertiesUtils {
                            display = "flex"; justifyContent = "space-between"; alignItems = "flex-start"
                          })(
                            mui.Box(
                              mui
                                .Typography("From statement")
                                .sx(new CSSPropertiesUtils {
                                  fontSize = "11px"; fontWeight = "700"; letterSpacing = "0.06em"
                                  textTransform = "uppercase"; color = "var(--lc-text-2)"; display = "block"
                                }),
                              mui
                                .Typography(row.description)
                                .sx(new CSSPropertiesUtils {
                                  fontFamily = "\"IBM Plex Mono\", monospace"; fontSize = "13.5px"
                                  marginTop = "4px"; display = "block"
                                }),
                              mui
                                .Typography(row.date.toString)
                                .sx(new CSSPropertiesUtils {
                                  fontSize = "12px"; color = "var(--lc-text-2)"; display = "block"
                                })
                            ),
                            mui
                              .Typography(s"${if (row.amount > 0) "+" else ""}${row.amount}")
                              .sx(new CSSPropertiesUtils {
                                fontWeight = "700"; fontSize = "18px"
                                fontFamily = "\"IBM Plex Mono\", monospace"
                                color = if (row.amount > 0) "var(--lc-brand)" else "var(--lc-error)"
                              })
                          )
                        ),
                        if (creatingForRow.contains(row.id))
                          BankRowCreateExpenseForm(
                            BankRowCreateExpenseForm.Props(
                              stmtId = stmt.id,
                              row = row,
                              merchants = merchantList,
                              categories = categoryList,
                              ruleList = ruleList,
                              aiSuggestion = stmt.aiSuggestions.flatMap(_.rows.find(_.rowId == row.id)),
                              ctx = props.ctx,
                              onSuccess = expenseId => {
                                setStatementRows(cur =>
                                  cur.map(r => if (r.id == row.id) r.copy(expenseId = Some(expenseId)) else r)
                                )
                                setCreatingForRow(None)
                                showToast("Expense created")
                              },
                              onCancel = () => setCreatingForRow(None),
                              onNewEntities = (newRule, newMerchant) => {
                                newRule.foreach(r => setRuleList(cur => cur :+ r))
                                newMerchant.foreach(m => setMerchantList(cur => cur :+ m))
                              }
                            )
                          )
                        else if (creatingRefundForRow.contains(row.id))
                          BankRowCreateRefundForm(
                            BankRowCreateRefundForm.Props(
                              stmtId = stmt.id,
                              rowId = row.id,
                              row = row,
                              merchants = merchantList,
                              categories = categoryList,
                              ruleList = ruleList,
                              ctx = props.ctx,
                              onSuccess = refundId => {
                                setStatementRows(cur =>
                                  cur.map(r => if (r.id == row.id) r.copy(refundId = Some(refundId)) else r)
                                )
                                setCreatingRefundForRow(None)
                                showToast("Refund created")
                              },
                              onCancel = () => setCreatingRefundForRow(None)
                            )
                          )
                        else if (linkingForRow.contains(row.id))
                          BankRowLinkPicker(
                            BankRowLinkPicker.Props(
                              stmtId = stmt.id,
                              rowId = row.id,
                              ctx = props.ctx,
                              onSuccess = expenseId => {
                                setStatementRows(cur =>
                                  cur.map(r => if (r.id == row.id) r.copy(expenseId = Some(expenseId)) else r)
                                )
                                setLinkingForRow(None)
                              },
                              onCancel = () => setLinkingForRow(None)
                            )
                          )
                        else if (ruleFormForRow.contains(row.id))
                          BankRowRuleForm(
                            BankRowRuleForm.Props(
                              stmtId = stmt.id,
                              row = row,
                              merchants = merchantList,
                              categories = categoryList,
                              ctx = props.ctx,
                              onSuccess = (rule, newMerchantOpt) => {
                                setRuleList(cur => cur :+ rule)
                                newMerchantOpt.foreach(m => setMerchantList(cur => cur :+ m))
                                setRuleFormForRow(None)
                                showToast("Rule created")
                              },
                              onCancel = () => setRuleFormForRow(None)
                            )
                          )
                        else Fragment()
                      ): ReactElement)
                    }
                    .getOrElse(Fragment())
                )
              ),
            // ── Toast ──
            toast match {
              case None => Fragment()
              case Some(msg) =>
                mui.Snackbar
                  .open(true)
                  .autoHideDuration(3000.0)
                  .onClose((_, _) => setToast(None))(
                    mui.Alert.severity("success")(msg)
                  )
            }
          )
      }
  }
}
