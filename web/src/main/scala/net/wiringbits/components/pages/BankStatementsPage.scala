package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{BankStatementDraft, GetBankStatement, GetBankStatements}
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{accept, input, multiple, onChange, `type`}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

object BankStatementsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
      AsyncComponent[GetBankStatements.Response](
        fetch = () => props.ctx.api.client.getBankStatements,
        render = stmts => BankStatementList(props.ctx, stmts, () => forceRefresh()),
        progressIndicator = () => mui.CircularProgress(),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object BankStatementList {
  def apply(
      ctx: AppContext,
      stmts: GetBankStatements.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, stmts, onRefresh))

  case class Props(
      ctx: AppContext,
      stmts: GetBankStatements.Response,
      onRefresh: () => Unit
  )

  private val PageSize = 15

  def productLabel(productType: String): String = productType match {
    case "credit" => "Credit"
    case "debit" => "Debit"
    case other => other
  }

  // An "account" groups a user's statements by bank + product type + last 4 digits.
  def accountKeyOf(s: GetBankStatements.Response.BankStatement): String =
    s"${s.bankName}~${s.productType}~${s.accountLast4}"

  def accountLabelOf(s: GetBankStatements.Response.BankStatement): String =
    s"${s.bankName} · ${productLabel(s.productType)} · ••••${s.accountLast4}"

  /** One-line metadata summary for a statement. */
  def metadataLine(s: GetBankStatements.Response.BankStatement): String =
    List(
      s.bankName,
      productLabel(s.productType),
      s.currency,
      s"••••${s.accountLast4}",
      s"${s.periodStart} → ${s.periodEnd}"
    ).mkString(" · ")

  /** A warning element when two consecutive statements of an account leave a gap (a whole cycle missing). `newer` and
    * `older` are adjacent in the period-sorted list.
    */
  def gapWarning(
      newer: GetBankStatements.Response.BankStatement,
      older: GetBankStatements.Response.BankStatement
  ): Option[ReactElement] = {
    val start = newer.periodStart
    val end = older.periodEnd
    Option.when(start.toEpochDay - end.toEpochDay > 20)(
      mui.Box
        .sx(new CSSPropertiesUtils {
          border = "1px dashed var(--lc-warning-text)"; borderRadius = "8px"; padding = "8px 16px";
          marginBottom = "6px"; backgroundColor = "var(--lc-paper)"
        })(
          mui
            .Typography(s"⚠ Gap — a statement between $end and $start appears to be missing")
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-warning-text)" })
        )
        .withKey(s"gap-${older.id}")
    )
  }

  private def toListStmt(r: GetBankStatement.Response): GetBankStatements.Response.BankStatement =
    GetBankStatements.Response.BankStatement(
      id = r.id,
      documentFilename = r.documentFilename,
      documentStatus = r.documentStatus,
      bankName = r.bankName,
      productType = r.productType,
      currency = r.currency,
      accountLast4 = r.accountLast4,
      periodStart = r.periodStart,
      periodEnd = r.periodEnd,
      createdAt = r.createdAt,
      totalRows = r.statementRows.size,
      processedRows =
        r.statementRows.count(row => row.expenseId.isDefined || row.refundId.isDefined || row.ignoredAt.isDefined)
    )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()

    val (error, setError) = Hooks.useState("")
    val (toast, setToast) = Hooks.useState[Option[String]](None)
    val (pollingIds, setPollingIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (stmtFilter, setStmtFilter) = Hooks.useState("")
    val (sortBy, setSortBy) = Hooks.useState("pending") // "pending" | "newest" | "oldest"
    val (showAll, setShowAll) = Hooks.useState(false)
    val (page, setPage) = Hooks.useState(0)
    val (accountFilter, setAccountFilter) = Hooks.useState("") // "" = all accounts; else an account key
    val (typeFilter, setTypeFilter) = Hooks.useState("") // "" = all types; else "credit"/"debit"

    // The import queue: drafts being AI-extracted / awaiting review.
    val (drafts, setDrafts) = Hooks.useState[List[BankStatementDraft.Summary]](Nil)
    val (uploadBusy, setUploadBusy) = Hooks.useState(false)

    def refreshDrafts(): Unit =
      props.ctx.api.client.listBankStatementDrafts.onComplete {
        case Success(r) => setDrafts(r.drafts)
        case Failure(_) => ()
      }

    Hooks.useEffect(() => { refreshDrafts(); () }, "")

    val draftsProcessingKey = drafts.filter(_.status == "processing").map(_.id.toString).sorted.mkString(",")
    Hooks.useEffect(
      () => {
        if (draftsProcessingKey.nonEmpty) {
          val timer = dom.window.setInterval(() => refreshDrafts(), 3000)
          () => { dom.window.clearInterval(timer); () }
        } else () => ()
      },
      js.Array(draftsProcessingKey)
    )

    def handleDraftUpload(inputEl: dom.HTMLInputElement): Unit = {
      val fileList = inputEl.files
      inputEl.value = ""
      val files = (0 until fileList.length).map(fileList(_)).toList
      if (files.nonEmpty) {
        setUploadBusy(true); setError("")
        def loop(remaining: List[dom.File]): Unit = remaining match {
          case Nil => setUploadBusy(false); refreshDrafts()
          case file :: rest =>
            val reader = new dom.FileReader()
            reader.readAsArrayBuffer(file)
            reader.onload = { _ =>
              val bytes = new js.typedarray.Int8Array(reader.result.asInstanceOf[js.typedarray.ArrayBuffer]).toArray
              props.ctx.api.client.createBankStatementDraft(bytes, file.name).onComplete {
                case Success(_) => refreshDrafts(); loop(rest)
                case Failure(ex) => setError(s"${file.name}: ${ex.getMessage}"); loop(rest)
              }
            }
            reader.onerror = { _ =>
              setError(s"${file.name}: file read error"); loop(rest)
            }
        }
        loop(files)
      }
    }

    def handleDiscardDraft(draftId: UUID): Unit =
      props.ctx.api.client.discardBankStatementDraft(draftId).onComplete {
        case Success(_) => refreshDrafts()
        case Failure(ex) => setError(ex.getMessage)
      }

    def updateFilter(v: String): Unit = { setStmtFilter(v); setPage(0) }
    def cycleSortBy(): Unit = {
      setSortBy(sortBy match { case "pending" => "newest"; case "newest" => "oldest"; case _ => "pending" })
      setPage(0)
    }

    val (localStmts, setLocalStmts) = Hooks.useState(props.stmts.data)
    val stmtsKey = props.stmts.data.map(s => s"${s.id}:${s.documentStatus.entryName}").mkString("|")
    Hooks.useEffect(
      () => { setLocalStmts(props.stmts.data); () },
      js.Array(stmtsKey)
    )

    val pendingStmts = localStmts.filter(s =>
      s.documentStatus == DocumentStatus.Ingested ||
        s.documentStatus == DocumentStatus.Processing ||
        s.documentStatus == DocumentStatus.Analyzed
    )
    val pendingKey = pendingStmts.map(_.id.toString).sorted.mkString(",")
    Hooks.useEffect(
      () => {
        if (pendingStmts.nonEmpty) {
          val timer = dom.window.setInterval(
            () =>
              pendingStmts.foreach { pending =>
                setPollingIds(cur => cur + pending.id)
                props.ctx.api.client.getBankStatement(pending.id).onComplete {
                  case Success(updated) =>
                    setPollingIds(cur => cur - updated.id)
                    setLocalStmts(cur => cur.map(s => if (s.id == updated.id) toListStmt(updated) else s))
                  case Failure(_) =>
                    setPollingIds(cur => cur - pending.id)
                }
              },
            3000
          )
          () => { dom.window.clearInterval(timer); () }
        } else { () => () }
      },
      js.Array(pendingKey)
    )

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    def handleDelete(bankStatementId: UUID): Unit =
      props.ctx.api.client
        .deleteBankStatement(bankStatementId)
        .onComplete {
          case Success(_) => setLocalStmts(cur => cur.filterNot(_.id == bankStatementId))
          case Failure(ex) => setError(ex.getMessage)
        }

    val accounts = localStmts.map(s => accountKeyOf(s) -> accountLabelOf(s)).distinctBy(_._1)
    val accountView = accountFilter.nonEmpty

    val displayedStmts = {
      val afterAccount =
        if (accountFilter.isEmpty) localStmts else localStmts.filter(s => accountKeyOf(s) == accountFilter)
      val afterType = if (typeFilter.isEmpty) afterAccount else afterAccount.filter(_.productType == typeFilter)
      val afterShowAll =
        if (showAll) afterType
        else afterType.filter(s => !(s.totalRows > 0 && s.processedRows == s.totalRows))
      val filtered =
        if (stmtFilter.trim.isEmpty) afterShowAll
        else afterShowAll.filter(_.documentFilename.toLowerCase.contains(stmtFilter.trim.toLowerCase))
      // In the account view, order by statement period (newest first) so gaps between cycles are visible.
      if (accountView) filtered.sortBy(s => s.periodStart.toEpochDay).reverse
      else
        sortBy match {
          case "pending" => filtered.sortBy(s => -(s.totalRows - s.processedRows))
          case "oldest" => filtered.reverse
          case _ => filtered // newest first (server default)
        }
    }

    val totalPages = math.max(1, (displayedStmts.size + PageSize - 1) / PageSize)
    // The account view shows the whole timeline (no paging) so gap detection stays continuous.
    val pagedStmts = if (accountView) displayedStmts else displayedStmts.slice(page * PageSize, (page + 1) * PageSize)

    val cards: Seq[ReactElement] = pagedStmts.map { s =>
      val isPending = s.documentStatus == DocumentStatus.Ingested ||
        s.documentStatus == DocumentStatus.Processing ||
        s.documentStatus == DocumentStatus.Analyzed
      val isPolling = pollingIds.contains(s.id)
      val statusColor = s.documentStatus match {
        case DocumentStatus.Parsed => "var(--lc-brand)"
        case DocumentStatus.ParseFailed | DocumentStatus.Unsupported => "var(--lc-error)"
        case DocumentStatus.Processing | DocumentStatus.Analyzed => "var(--lc-link)"
        case _ => "var(--lc-text-2)"
      }

      val content: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"
        alignItems = "center"
        justifyContent = "space-between"
        width = "100%"
      })(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
            mui
              .Typography(s.documentFilename)
              .sx(new CSSPropertiesUtils {
                fontSize = "14px"
                fontWeight = "600"
                color = "var(--lc-text)"
                fontFamily = "\"IBM Plex Sans\", sans-serif"
              }),
            if (isPolling) mui.CircularProgress().size(14.0) else Fragment()
          ),
          mui
            .Typography(s.documentStatus.entryName)
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = statusColor }),
          mui
            .Typography(metadataLine(s))
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }),
          if (isPending) mui.LinearProgress().sx(new CSSPropertiesUtils { width = "200px"; marginTop = "4px" })
          else Fragment(),
          if (s.totalRows > 0) {
            val pending = s.totalRows - s.processedRows
            if (pending == 0)
              mui
                .Typography("All done")
                .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-brand)" })
            else
              mui
                .Typography(s"$pending pending")
                .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-warning-text)" })
          } else Fragment()
        ),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px"; alignItems = "center" })(
          if (s.documentStatus == DocumentStatus.Parsed)
            mui.Button
              .normal()("Review")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
              .onClick(_ => history.push(s"/bank-statements/${s.id}"))
          else Fragment(),
          mui.Button
            .normal()("Delete")
            .size("small")
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
            .onClick(_ => handleDelete(s.id))
        )
      )

      val reviewPath = s"/bank-statements/${s.id}"
      val isReviewable = s.documentStatus == DocumentStatus.Parsed
      val cardSx = new CSSPropertiesUtils {
        border = "1px solid var(--lc-border)"
        borderRadius = "8px"
        backgroundColor = "var(--lc-paper)"
        marginBottom = "6px"
        padding = "12px 16px"
        width = "100%"
        cursor = if (isReviewable) "pointer" else "default"
      }
      mui.Box
        .sx(cardSx)
        .onClick(e => {
          if (isReviewable) {
            if (e.ctrlKey || e.metaKey) dom.window.open(reviewPath, "_blank")
            else history.push(reviewPath)
          }
        })(content)
        .withKey(s.id.toString)
    }

    // In the account view, interleave a warning wherever consecutive statements leave a gap in the period sequence.
    val stmtItems: Seq[ReactElement] =
      if (!accountView) cards
      else
        cards.zipWithIndex.flatMap { case (card, i) =>
          val gap = if (i + 1 < pagedStmts.size) gapWarning(pagedStmts(i), pagedStmts(i + 1)).toSeq else Nil
          card +: gap
        }

    Fragment(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; justifyContent = "space-between"; marginBottom = "12px"
      })(
        mui
          .Typography("Bank Statements")
          .sx(new CSSPropertiesUtils { fontSize = "16px"; fontWeight = "700"; color = "var(--lc-text)" }),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
          mui.Button
            .normal()("Bulk expenses")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push("/bank-statements/bulk-expenses")),
          mui.Button
            .normal()("Bulk merchant setup")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push("/bank-statements/bulk-setup"))
        )
      ),
      // Import (AI): upload one or more PDFs → each is extracted into a draft to review before it becomes a statement.
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-brand)"; borderRadius = "8px"; padding = "16px";
        backgroundColor = "var(--lc-paper)"; marginBottom = "16px"
      })(
        if (uploadBusy)
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "10px" })(
            mui.CircularProgress().size(20.0),
            mui.Typography("Uploading…").sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" })
          )
        else
          Fragment(
            input(
              `type` := "file",
              multiple := true,
              accept := ".pdf,application/pdf",
              onChange := { e => handleDraftUpload(e.target.asInstanceOf[dom.HTMLInputElement]) }
            ),
            mui
              .Typography(
                "Import statements — select one or more PDFs; each is extracted with AI and queued below for review."
              )
              .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)"; marginTop = "8px" })
          )
      ),
      if (drafts.nonEmpty)
        mui.Box.sx(new CSSPropertiesUtils { marginBottom = "16px" })(
          Fragment(
            (mui
              .Typography(s"Pending imports (${drafts.size})")
              .sx(new CSSPropertiesUtils {
                fontSize = "13px"; fontWeight = "600"; color = "var(--lc-text-2)"; marginBottom = "6px"
              }): ReactElement) ::
              drafts.map { d =>
                val (statusText, statusColor) = d.status match {
                  case "ready" => ("Ready to review", "var(--lc-brand)")
                  case "failed" => (s"Failed — ${d.error.getOrElse("")}", "var(--lc-error)")
                  case _ => ("Extracting…", "var(--lc-link)")
                }
                (mui.Box
                  .sx(new CSSPropertiesUtils {
                    border = "1px solid var(--lc-border)"; borderRadius = "8px"; backgroundColor = "var(--lc-paper)";
                    marginBottom = "6px"; padding = "10px 16px"; display = "flex"; alignItems = "center";
                    justifyContent = "space-between"
                  })(
                    mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
                      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                        mui.Typography(d.filename).sx(new CSSPropertiesUtils { fontSize = "14px"; fontWeight = "600" }),
                        if (d.status == "processing") mui.CircularProgress().size(14.0) else Fragment()
                      ),
                      mui.Typography(statusText).sx(new CSSPropertiesUtils { fontSize = "12px"; color = statusColor })
                    ),
                    mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
                      if (d.status == "ready")
                        mui.Button
                          .normal()("Review")
                          .size("small")
                          .variant("contained")
                          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                          .onClick(_ => history.push(s"/bank-statements/import/${d.id}"))
                      else Fragment(),
                      mui.Button
                        .normal()("Discard")
                        .size("small")
                        .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                        .onClick(_ => handleDiscardDraft(d.id))
                    )
                  )
                  .withKey(d.id.toString): ReactElement)
              }*
          )
        )
      else Fragment(),
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; flexWrap = "wrap"; gap = "8px"; marginBottom = "12px"
      })(
        if (accounts.nonEmpty)
          mui.TextField
            .outlined()
            .select(true)
            .label("Account")
            .size("small")
            .value(accountFilter)
            .onChange(e => { setAccountFilter(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
            .sx(new CSSPropertiesUtils { minWidth = "220px" })(
              (("" -> "All accounts") +: accounts).map { case (k, label) =>
                mui.MenuItem.normal()(label).value(k).withKey(if (k.isEmpty) "all" else k).build
              }*
            )
        else Fragment(),
        mui.TextField
          .outlined()
          .select(true)
          .label("Type")
          .size("small")
          .value(typeFilter)
          .onChange(e => { setTypeFilter(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
          .sx(new CSSPropertiesUtils { minWidth = "130px" })(
            List("" -> "All types", "credit" -> "Credit", "debit" -> "Debit").map { case (k, label) =>
              mui.MenuItem.normal()(label).value(k).withKey(if (k.isEmpty) "alltypes" else k).build
            }*
          ),
        mui.TextField
          .outlined()
          .label("Filter by name")
          .size("small")
          .value(stmtFilter)
          .onChange(e => updateFilter(e.target.asInstanceOf[dom.HTMLInputElement].value))
          .sx(new CSSPropertiesUtils { flex = "1"; minWidth = "160px" }),
        mui.Button
          .normal()(sortBy match {
            case "pending" => "Most pending first"
            case "newest" => "Newest first"
            case _ => "Oldest first"
          })
          .size("small")
          .variant("outlined")
          .onClick(_ => cycleSortBy()),
        mui.Button
          .normal()(if (showAll) "Show pending only" else "Show all")
          .size("small")
          .variant("outlined")
          .onClick(_ => { setShowAll(!showAll); setPage(0) })
      ),
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      Fragment(stmtItems*),
      if (accountView) Fragment()
      else
        mui.Box.sx(new CSSPropertiesUtils { marginTop = "8px" })(
          mui.Pagination
            .withProps(
              js.Dynamic
                .literal(
                  count = totalPages,
                  page = page + 1,
                  onChange = ((e: js.Any, p: Double) => setPage(p.toInt - 1)): js.Function2[js.Any, Double, Unit]
                )
                .asInstanceOf[com.olvind.mui.muiMaterial.paginationPaginationMod.PaginationProps]
            )
        ),
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
