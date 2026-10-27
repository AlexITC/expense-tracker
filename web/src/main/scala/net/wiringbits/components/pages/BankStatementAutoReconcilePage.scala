package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{GetBankStatement, LinkRowToExpense}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.widgets.Loader
import net.wiringbits.models.User
import net.wiringbits.utils.ReconciliationMatcher
import net.wiringbits.utils.ReconciliationMatcher.ProposedAction
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useParams
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object BankStatementAutoReconcilePage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh
    val params = useParams()
    val stmtIdOpt = Try(UUID.fromString(params.get("stmtId").getOrElse(""))).toOption

    stmtIdOpt match {
      case None =>
        mui.Typography("Invalid statement ID").sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      case Some(stmtId) =>
        AsyncComponent[GetBankStatement.Response](
          fetch = () => props.ctx.api.client.getBankStatement(stmtId),
          render = stmt =>
            AsyncComponent[GetExpenses.Response](
              fetch = () => props.ctx.api.client.getExpenses,
              render = expenses => ReconcileContent(props.ctx, stmt, expenses, () => forceRefresh()),
              progressIndicator = () => Loader(props.ctx),
              watchedObjects = List(timesRefreshing)
            ),
          progressIndicator = () => Loader(props.ctx),
          watchedObjects = List(timesRefreshing)
        )
    }
  }
}

object ReconcileContent {
  private val NoSelectionValue = ""
  private val CreateNewValue = "create-new"

  def apply(
      ctx: AppContext,
      stmt: GetBankStatement.Response,
      expenses: GetExpenses.Response,
      onRefresh: () => Unit
  ): KeyAddingStage = component(Props(ctx, stmt, expenses, onRefresh))

  case class Props(
      ctx: AppContext,
      stmt: GetBankStatement.Response,
      expenses: GetExpenses.Response,
      onRefresh: () => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    // Compute reconciliation once at mount. Rules load asynchronously below;
    // rows without a matching rule fall to CreateNew.
    val initial = Hooks.useMemo(
      () => ReconciliationMatcher.reconcile(props.stmt.statementRows, props.expenses.data, Nil, Nil),
      ""
    )
    val (rowActions, setRowActions) = Hooks.useState(initial)
    val (ruleList, setRuleList) = Hooks.useState[List[ImportRuleMatcher.Rule]](Nil)
    val (merchantList, setMerchantList) = Hooks.useState[List[GetMerchants.Response.Merchant]](Nil)
    val (submitting, setSubmitting) = Hooks.useState(false)
    val (applyError, setApplyError) = Hooks.useState[Option[String]](None)

    Hooks.useEffect(
      () => {
        props.ctx.api.client.getImportRules.onComplete {
          case Success(resp) =>
            setRuleList(
              resp.data.map(r => ImportRuleMatcher.Rule(r.id, r.action, r.pattern, r.merchantId, r.categoryId))
            )
          case Failure(_) => ()
        }
        props.ctx.api.client.getMerchants.onComplete {
          case Success(resp) => setMerchantList(resp.data)
          case Failure(_) => ()
        }
        ()
      },
      js.Array("")
    )

    def setRowAction(rowId: UUID, action: ProposedAction): Unit =
      setRowActions(cur => cur.updated(rowId, action))

    // ── Derived data (pure, not React elements) ──────────────────────────────

    val expenseById: Map[UUID, GetExpenses.Response.Expense] =
      props.expenses.data.map(e => e.id -> e).toMap

    val eligible: List[GetBankStatement.StatementRowData] =
      props.stmt.statementRows.filter(r => r.expenseId.isEmpty && r.ignoredAt.isEmpty)

    val autoLinkPairs: List[(GetBankStatement.StatementRowData, UUID)] = eligible.flatMap { row =>
      rowActions.get(row.id) match {
        case Some(ReconciliationMatcher.AutoLink(expId)) => Some(row -> expId)
        case _ => None
      }
    }

    val needsReviewPairs: List[(GetBankStatement.StatementRowData, List[UUID])] = eligible.flatMap { row =>
      rowActions.get(row.id) match {
        case Some(ReconciliationMatcher.NeedsReview(candidates)) => Some(row -> candidates)
        case _ => None
      }
    }

    val createNewRows: List[GetBankStatement.StatementRowData] = eligible.filter { row =>
      rowActions.get(row.id).contains(ReconciliationMatcher.CreateNew)
    }

    val qualifyingCreateNewRows =
      ReconciliationMatcher.qualifyingCreateRequests(createNewRows, ruleList, merchantList)

    val applyCount = autoLinkPairs.size + qualifyingCreateNewRows.size

    def handleApply(): Unit = {
      setSubmitting(true)
      val stmtId = props.stmt.id
      val linkFutures: List[Future[Unit]] = autoLinkPairs.map { case (row, expId) =>
        props.ctx.api.client.linkRowToExpense(stmtId, row.id, LinkRowToExpense.Request(expId)).map(_ => ())
      }
      val createFutures: List[Future[Unit]] = qualifyingCreateNewRows.map { case (row, req) =>
        props.ctx.api.client.createExpenseFromRow(stmtId, row.id, req).map(_ => ())
      }
      Future.sequence(linkFutures ++ createFutures).onComplete {
        case Success(_) =>
          setSubmitting(false)
          props.onRefresh()
        case Failure(ex) =>
          setSubmitting(false)
          setApplyError(Some(ex.getMessage))
          props.onRefresh()
      }
    }

    val hcell: CSSPropertiesUtils = new CSSPropertiesUtils {
      fontWeight = "700"; fontSize = "11px"; letterSpacing = "0.06em"
      textTransform = "uppercase"; color = "var(--lc-text-2)"
    }

    // ── Element helpers (def — called per-row in map loops) ──────────────────

    def sectionHeader(title: String, count: Int): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        padding = "16px 0 8px 0"; borderBottom = "2px solid var(--lc-brand)"
        display = "flex"; alignItems = "center"; gap = "8px"; marginTop = "8px"
      })(
        mui
          .Typography(title)
          .sx(new CSSPropertiesUtils {
            fontWeight = "700"; fontSize = "12px"; letterSpacing = "0.08em"
            textTransform = "uppercase"; color = "var(--lc-brand)"
          }),
        mui.Chip
          .label(count.toString)
          .size("small")
          .sx(new CSSPropertiesUtils {
            height = "20px"; fontSize = "11px"
            backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
          })
      )

    def expenseLabel(expId: UUID): String =
      expenseById
        .get(expId)
        .map(e => s"${e.amount} · ${e.date} · ${e.merchantName.getOrElse("—")}")
        .getOrElse("—")

    def amountCell(amount: BigDecimal): ReactElement = {
      val sign = if (amount > 0) "+" else ""
      val amtColor = if (amount > 0) "var(--lc-brand)" else "var(--lc-error)"
      (mui
        .Typography(s"$sign$amount")
        .sx(new CSSPropertiesUtils {
          fontWeight = "700"; fontSize = "13px"; color = amtColor
          fontFamily = "\"IBM Plex Mono\", monospace"
        }): ReactElement)
    }

    def dateCell(row: GetBankStatement.StatementRowData): ReactElement =
      (mui.TableCell.sx(new CSSPropertiesUtils {
        fontFamily = "\"IBM Plex Mono\", monospace"
        fontSize = "13px"; color = "var(--lc-text-2)"; whiteSpace = "nowrap"
      })(row.date.toString): ReactElement)

    def descCell(row: GetBankStatement.StatementRowData): ReactElement =
      (mui.TableCell.sx(new CSSPropertiesUtils { maxWidth = "240px" })(
        mui
          .Typography(row.description)
          .sx(new CSSPropertiesUtils {
            fontSize = "13px"; fontFamily = "\"IBM Plex Mono\", monospace"
            overflow = "hidden"; textOverflow = "ellipsis"; whiteSpace = "nowrap"; display = "block"
          })
      ): ReactElement)

    def autoLinkRow(row: GetBankStatement.StatementRowData, expId: UUID): ReactElement = {
      val expLabel = expenseLabel(expId)
      (mui.TableRow
        .hover(true)(
          dateCell(row),
          mui.TableCell.align("right")(amountCell(row.amount)),
          descCell(row),
          mui.TableCell(
            mui
              .Typography(expLabel)
              .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" })
          ),
          mui.TableCell.align("right")(
            mui.Button
              .normal()("Override → Create New")
              .size("small")
              .variant("outlined")
              .onClick(_ => setRowAction(row.id, ReconciliationMatcher.CreateNew))
              .build
          )
        )
        .withKey(row.id.toString): ReactElement)
    }

    def needsReviewRow(row: GetBankStatement.StatementRowData, candidates: List[UUID]): ReactElement = {
      val placeholderItem =
        mui.MenuItem.normal()("Select candidate…").value(NoSelectionValue).withKey("placeholder").build
      val candidateItems = candidates.map { expId =>
        mui.MenuItem.normal()(expenseLabel(expId)).value(expId.toString).withKey(expId.toString).build
      }
      val createNewItem =
        mui.MenuItem.normal()("Create New").value(CreateNewValue).withKey(CreateNewValue).build
      val allItems = placeholderItem +: candidateItems :+ createNewItem

      (mui.TableRow
        .hover(true)(
          dateCell(row),
          mui.TableCell.align("right")(amountCell(row.amount)),
          descCell(row),
          mui.TableCell.colSpan(2)(
            mui.TextField
              .outlined()
              .select(true)
              .size("small")
              .label("Pick candidate")
              .value(NoSelectionValue)
              .onChange(e => {
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                if (v == CreateNewValue) setRowAction(row.id, ReconciliationMatcher.CreateNew)
                else if (v.nonEmpty)
                  setRowAction(row.id, ReconciliationMatcher.AutoLink(UUID.fromString(v)))
              })
              .sx(new CSSPropertiesUtils { minWidth = "280px" })(allItems*)
          )
        )
        .withKey(row.id.toString): ReactElement)
    }

    def createNewRow(row: GetBankStatement.StatementRowData): ReactElement =
      (mui.TableRow
        .hover(true)(
          dateCell(row),
          mui.TableCell.align("right")(amountCell(row.amount)),
          descCell(row),
          mui.TableCell.colSpan(2)(
            mui.Chip
              .label("Create New")
              .size("small")
              .sx(new CSSPropertiesUtils {
                height = "22px"; fontSize = "12px"
                backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
              })
          )
        )
        .withKey(row.id.toString): ReactElement)

    // ── Layout ───────────────────────────────────────────────────────────────

    mui.Box.sx(new CSSPropertiesUtils { overflowY = "auto"; padding = "0 24px 24px 24px" })(
      // ── Apply button ──
      if (applyCount > 0)
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; justifyContent = "flex-end"; padding = "16px 0 8px 0"
        })(
          mui.Button
            .normal()(s"Apply ($applyCount actions)")
            .variant("contained")
            .disabled(submitting)
            .onClick(_ => handleApply())
            .build
        )
      else Fragment(),
      // ── Auto-Link section ──
      if (autoLinkPairs.nonEmpty)
        Fragment(
          sectionHeader("Auto-Link", autoLinkPairs.size),
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell.sx(hcell)("Date"),
                mui.TableCell.align("right").sx(hcell)("Amount"),
                mui.TableCell.sx(hcell)("Description"),
                mui.TableCell.sx(hcell)("Matched Expense"),
                mui.TableCell.align("right").sx(hcell)("")
              )
            ),
            mui.TableBody(
              Fragment(autoLinkPairs.map { case (row, expId) => autoLinkRow(row, expId) }*)
            )
          )
        )
      else Fragment(),
      // ── Needs Review section ──
      if (needsReviewPairs.nonEmpty)
        Fragment(
          sectionHeader("Needs Review", needsReviewPairs.size),
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell.sx(hcell)("Date"),
                mui.TableCell.align("right").sx(hcell)("Amount"),
                mui.TableCell.sx(hcell)("Description"),
                mui.TableCell.colSpan(2).sx(hcell)("Select Candidate")
              )
            ),
            mui.TableBody(
              Fragment(needsReviewPairs.map { case (row, candidates) => needsReviewRow(row, candidates) }*)
            )
          )
        )
      else Fragment(),
      // ── Create New section ──
      if (createNewRows.nonEmpty)
        Fragment(
          sectionHeader("Create New", createNewRows.size),
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell.sx(hcell)("Date"),
                mui.TableCell.align("right").sx(hcell)("Amount"),
                mui.TableCell.sx(hcell)("Description"),
                mui.TableCell.colSpan(2).sx(hcell)("")
              )
            ),
            mui.TableBody(
              Fragment(createNewRows.map(createNewRow)*)
            )
          )
        )
      else Fragment(),
      // ── Error snackbar ──
      applyError match {
        case None => Fragment()
        case Some(msg) =>
          mui.Snackbar
            .open(true)
            .autoHideDuration(5000.0)
            .onClose((_, _) => setApplyError(None))(
              mui.Alert.severity("error")(msg)
            )
      }
    )
  }
}
