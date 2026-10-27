package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.GetAllRows
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.{MuiCompat, RouterCompat}
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom.URLSearchParams
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success}

object BulkMerchantSetupPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage = component(Props(ctx, user))

  case class Props(ctx: AppContext, user: User)

  private val PageSize = 25

  // Rows covered by an explicit rule or by an existing merchant name are not unmatched.
  private[pages] def findUnmatchedGroups(
      rows: List[GetAllRows.RowData],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): List[(String, List[GetAllRows.RowData])] = {
    val effective = ImportRuleMatcher.effectiveRules(rules, merchants.map(m => m.id -> m.name))
    rows
      .filter(r => r.amount < 0 && r.expenseId.isEmpty && r.ignoredAt.isEmpty)
      .filterNot(r => ImportRuleMatcher.findRowWinner(effective, r.description).isDefined)
      .groupBy(_.description)
      .view
      .mapValues(_.sortBy(_.date))
      .toList
      .sortBy(-_._2.size)
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()
    val location = RouterCompat.useTypedLocation()

    val initialPage = {
      val search = location.search
      val params = new URLSearchParams(search)
      val p = params.get("page")
      if (p == null) 0 else math.max(0, p.toIntOption.getOrElse(1) - 1)
    }

    val (loading, setLoading) = Hooks.useState(true)
    val (error, setError) = Hooks.useState("")
    val (allRows, setAllRows) = Hooks.useState[List[GetAllRows.RowData]](Nil)
    val (ruleList, setRuleList) = Hooks.useState[List[ImportRuleMatcher.Rule]](Nil)
    val (merchants, setMerchants) = Hooks.useState[List[GetMerchants.Response.Merchant]](Nil)
    val (categories, setCategories) = Hooks.useState[List[GetCategories.Response.Category]](Nil)
    val (page, setPage) = Hooks.useState(initialPage)
    val (pageChanging, setPageChanging) = Hooks.useState(false)

    Hooks.useEffect(
      () => { setPageChanging(false); () },
      List(page)
    )

    def gotoPage(n: Int): Unit = {
      setPageChanging(true)
      setPage(n)
      history.push(s"/bank-statements/bulk-setup?page=${n + 1}")
      dom.window.scrollTo(0, 0)
    }

    Hooks.useEffect(
      () => {
        val fRows = props.ctx.api.client.getAllBankStatementRows
        val fRules = props.ctx.api.client.getImportRules
        val fMerchants = props.ctx.api.client.getMerchants
        val fCats = props.ctx.api.client.getCategories

        (for {
          rowsResp <- fRows
          rulesResp <- fRules
          merchantsResp <- fMerchants
          catsResp <- fCats
        } yield (rowsResp, rulesResp, merchantsResp, catsResp)).onComplete {
          case Success((rowsResp, rulesResp, merchantsResp, catsResp)) =>
            setAllRows(rowsResp.data)
            setRuleList(
              rulesResp.data.map(ImportRuleMatcher.Rule.fromApi)
            )
            setMerchants(merchantsResp.data)
            setCategories(catsResp.data)
            setLoading(false)
          case Failure(ex) =>
            setError(ex.getMessage)
            setLoading(false)
        }
        ()
      },
      js.Array("")
    )

    val unmatchedGroups: List[(String, List[GetAllRows.RowData])] =
      findUnmatchedGroups(allRows, ruleList, merchants)

    val totalPages = math.max(1, math.ceil(unmatchedGroups.size.toDouble / PageSize).toInt)
    val safePage = math.min(page, math.max(0, totalPages - 1))
    val pagedGroups = unmatchedGroups.slice(safePage * PageSize, (safePage + 1) * PageSize)

    val hdrSx = new CSSPropertiesUtils {
      fontSize = "11px"; fontWeight = "700"; letterSpacing = "0.06em"
      textTransform = "uppercase"; color = "var(--lc-text-2)"
    }

    if (loading || pageChanging)
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(mui.CircularProgress())
    else if (error.nonEmpty)
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(
        mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      )
    else
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "20px"
        })(
          mui
            .Typography("Bulk merchant setup")
            .sx(new CSSPropertiesUtils { fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)" }),
          if (unmatchedGroups.nonEmpty)
            mui
              .Typography(s"${unmatchedGroups.size} unmatched descriptions")
              .sx(new CSSPropertiesUtils { fontSize = "14px"; color = "var(--lc-text-2)" })
          else Fragment(),
          mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
          mui.Button
            .normal()("← Back")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push("/bank-statements"))
        ),
        if (unmatchedGroups.isEmpty)
          mui.Box.sx(new CSSPropertiesUtils {
            padding = "48px"; textAlign = "center"; border = "1px solid var(--lc-border)"; borderRadius = "8px"
          })(
            mui
              .Typography("All descriptions are matched.")
              .sx(new CSSPropertiesUtils { fontSize = "16px"; color = "var(--lc-text-2)"; marginBottom = "12px" }),
            mui.Button
              .normal()("Go to bank statements")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => history.push("/bank-statements"))
          )
        else
          Fragment(
            mui.Typography("Description").sx(hdrSx),
            Fragment(
              pagedGroups.map { case (description, rows) =>
                DescriptionRow(
                  DescriptionRow.Props(
                    description = description,
                    rows = rows,
                    merchants = merchants,
                    categories = categories,
                    ctx = props.ctx,
                    onDone = (rule, newMerchant, newCategory) => {
                      setRuleList(cur => cur :+ rule)
                      newMerchant.foreach(m => setMerchants(cur => cur :+ m))
                      newCategory.foreach(c => setCategories(cur => cur :+ c))
                    }
                  )
                ).withKey(description)
              }*
            ),
            mui.Box.sx(new CSSPropertiesUtils { marginTop = "16px" })(
              mui.Pagination
                .withProps(MuiCompat.paginationProps(count = totalPages, page = safePage, onChange = gotoPage))
            )
          )
      )
  }
}

object DescriptionRow {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      description: String,
      rows: List[GetAllRows.RowData],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ctx: AppContext,
      onDone: (
          ImportRuleMatcher.Rule,
          Option[GetMerchants.Response.Merchant],
          Option[GetCategories.Response.Category]
      ) => Unit
  )

  // Extracted so they can be unit-tested directly (jsdom doesn't open MUI dropdowns).
  // Ascribing opt as Any widens from js.Any — both String and Merchant are subtypes of Any,
  // so the compiler accepts the match cases. Do NOT use js.Dynamic.name: Scala.js case class
  // fields are name-mangled in JS output, so .name returns undefined.
  private[pages] def merchantOptionLabel(opt: js.Any): String =
    (opt: Any) match {
      case s: String => s
      case m: GetMerchants.Response.Merchant => m.name
      case _ => ""
    }

  private[pages] def categoryOptionLabel(opt: js.Any): String =
    (opt: Any) match {
      case s: String => s
      case c: GetCategories.Response.Category => c.name
      case _ => ""
    }

  // Picked id, else an existing category by case-insensitive name, else a newly created one (returned so the caller
  // can add it to its list).
  private[pages] def resolveOrCreateCategory(
      client: ApiClient,
      categories: List[GetCategories.Response.Category],
      pickedId: Option[UUID],
      name: String
  )(implicit ec: ExecutionContext): Future[(UUID, Option[GetCategories.Response.Category])] =
    pickedId match {
      case Some(id) => Future.successful((id, None))
      case None =>
        val trimmed = name.trim
        categories.find(_.name.equalsIgnoreCase(trimmed)) match {
          case Some(existing) => Future.successful((existing.id, None))
          case None =>
            client
              .createCategory(CreateCategory.Request(name = trimmed))
              .map(r =>
                (r.id, Some(GetCategories.Response.Category(id = r.id, name = r.name, createdAt = Instant.now())))
              )
        }
    }

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (expanded, setExpanded) = Hooks.useState(false)
    val (merchantInput, setMerchantInput) = Hooks.useState(props.description)
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (categoryInput, setCategoryInput) = Hooks.useState("")
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](None)
    val (saving, setSaving) = Hooks.useState(false)
    val (rowError, setRowError) = Hooks.useState("")

    def handleAssign(): Unit = {
      val merchantName = merchantInput.trim
      val catName = categoryInput.trim
      if (merchantName.isEmpty) {
        setRowError("Merchant name is required")
      } else if (catName.isEmpty) {
        setRowError("Category is required")
      } else {
        setSaving(true)
        setRowError("")

        val fResult: Future[Unit] = for {
          (resolvedCategoryId, newCategoryOpt) <- resolveOrCreateCategory(
            props.ctx.api.client,
            props.categories,
            categoryIdOpt,
            catName
          )

          merchantResult <- merchantIdOpt match {
            case Some(id) =>
              Future.successful((id, None: Option[GetMerchants.Response.Merchant]))
            case None =>
              props.merchants.find(_.name.equalsIgnoreCase(merchantName)) match {
                case Some(existing) =>
                  Future.successful((existing.id, None: Option[GetMerchants.Response.Merchant]))
                case None =>
                  props.ctx.api.client
                    .createMerchant(
                      CreateMerchant.Request(
                        name = merchantName,
                        notes = None,
                        categoryId = Some(resolvedCategoryId),
                        taxId = None
                      )
                    )
                    .map { r =>
                      val m = GetMerchants.Response.Merchant(
                        id = r.id,
                        name = r.name,
                        notes = None,
                        categoryId = Some(resolvedCategoryId),
                        taxId = None,
                        reviewed = false,
                        createdAt = Instant.now()
                      )
                      (r.id, Some(m))
                    }
              }
          }

          (resolvedMerchantId, newMerchantOpt) = merchantResult

          ruleResp <- props.ctx.api.client.createImportRule(
            CreateImportRule.Request(
              action = ImportRuleAction.SetMerchant,
              pattern = props.description,
              merchantId = Some(resolvedMerchantId)
            )
          )

          _ <-
            if (merchantName != props.description)
              props.ctx.api.client
                .createImportRule(
                  CreateImportRule.Request(
                    action = ImportRuleAction.SetMerchant,
                    pattern = merchantName,
                    merchantId = Some(resolvedMerchantId)
                  )
                )
                .recover { case _ => ruleResp }
            else Future.successful(ruleResp)
        } yield {
          val rule = ImportRuleMatcher.Rule(
            id = ruleResp.id,
            action = ImportRuleAction.SetMerchant,
            pattern = props.description,
            merchantId = Some(resolvedMerchantId)
          )
          props.onDone(rule, newMerchantOpt, newCategoryOpt)
        }

        fResult.onComplete {
          case Success(_) => setSaving(false)
          case Failure(ex) => setRowError(ex.getMessage); setSaving(false)
        }
      }
    }

    def handleIgnore(): Unit = {
      setSaving(true)
      setRowError("")
      props.ctx.api.client
        .createImportRule(
          CreateImportRule.Request(
            action = ImportRuleAction.Ignore,
            pattern = props.description,
            merchantId = None
          )
        )
        .onComplete {
          case Success(resp) =>
            val rule = ImportRuleMatcher.Rule(
              id = resp.id,
              action = ImportRuleAction.Ignore,
              pattern = props.description,
              merchantId = None
            )
            setSaving(false)
            props.onDone(rule, None, None)
          case Failure(ex) =>
            setRowError(ex.getMessage)
            setSaving(false)
        }
    }

    val merchantAcProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
      options = props.merchants.toJSArray,
      renderInput = renderTextField("Merchant")
    )
    val merchantDyn = merchantAcProps.asInstanceOf[js.Dynamic]
    merchantDyn.freeSolo = true
    merchantDyn.size = "small"
    merchantDyn.disabled = saving
    merchantDyn.inputValue = merchantInput
    merchantDyn.getOptionLabel = (opt: js.Any) => merchantOptionLabel(opt)
    merchantDyn.sx = new CSSPropertiesUtils { minWidth = "160px" }
    merchantDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case m: GetMerchants.Response.Merchant =>
          setMerchantIdOpt(Some(m.id))
          m.categoryId.foreach { cid =>
            setCategoryIdOpt(Some(cid))
            props.categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
          }
        case _ => ()
      }
    merchantDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setMerchantInput(text)
      if (rsn != "reset") setMerchantIdOpt(None)
    }

    val categoryAcProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
      options = props.categories.toJSArray,
      renderInput = renderTextField("Category *")
    )
    val categoryDyn = categoryAcProps.asInstanceOf[js.Dynamic]
    categoryDyn.freeSolo = true
    categoryDyn.size = "small"
    categoryDyn.disabled = saving
    categoryDyn.inputValue = categoryInput
    categoryDyn.getOptionLabel = (opt: js.Any) => categoryOptionLabel(opt)
    categoryDyn.sx = new CSSPropertiesUtils { minWidth = "140px" }
    categoryDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case c: GetCategories.Response.Category => setCategoryIdOpt(Some(c.id))
        case _ => ()
      }
    categoryDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setCategoryInput(text)
      if (rsn != "reset") setCategoryIdOpt(None)
    }

    mui.Box.sx(new CSSPropertiesUtils {
      border = "1px solid var(--lc-border)"; borderRadius = "8px"; padding = "12px 16px"
      marginBottom = "8px"; backgroundColor = "var(--lc-paper)"
    })(
      Fragment(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "8px" })(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
            mui
              .Typography(props.description)
              .sx(new CSSPropertiesUtils {
                fontFamily = "\"IBM Plex Mono\", monospace"; fontSize = "13px"
                fontWeight = "600"; color = "var(--lc-text)"; wordBreak = "break-all"
              }),
            mui.Chip
              .label(s"${props.rows.size} rows ${if (expanded) "▲" else "▼"}")
              .size("small")
              .onClick(_ => setExpanded(!expanded))
              .sx(new CSSPropertiesUtils {
                height = "20px"; fontSize = "11px"; backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
                cursor = "pointer"
              })
          ),
          if (expanded)
            mui.Box.sx(new CSSPropertiesUtils {
              display = "flex"; flexDirection = "column"; gap = "2px"
              padding = "6px 8px"; backgroundColor = "var(--lc-surface)"; borderRadius = "4px"
            })(
              Fragment(
                props.rows.zipWithIndex.map { case (r, i) =>
                  mui.Box
                    .sx(new CSSPropertiesUtils {
                      display = "flex"; gap = "16px"; fontSize = "12px"
                      fontFamily = "\"IBM Plex Mono\", monospace"; color = "var(--lc-text-2)"
                    })(
                      mui
                        .Typography(r.date.toString)
                        .sx(new CSSPropertiesUtils {
                          fontSize = "12px"; fontFamily = "\"IBM Plex Mono\", monospace"
                        }),
                      mui
                        .Typography(f"${r.amount}%.2f")
                        .sx(new CSSPropertiesUtils {
                          fontSize = "12px"; fontFamily = "\"IBM Plex Mono\", monospace"; color = "var(--lc-error)"
                        })
                    )
                    .withKey(i.toString)
                }*
              )
            )
          else Fragment(),
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; gap = "8px"; alignItems = "flex-start"; flexWrap = "wrap"
          })(
            (mui.Autocomplete.withProps(merchantAcProps).withKey("m"): ReactElement),
            (mui.Autocomplete.withProps(categoryAcProps).withKey("c"): ReactElement),
            mui.Button
              .normal()(if (saving) "Saving…" else "Assign")
              .size("small")
              .variant("contained")
              .disabled(saving)
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; minWidth = "80px" })
              .onClick(_ => handleAssign()),
            mui.Button
              .normal()("Ignore")
              .size("small")
              .disabled(saving)
              .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
              .onClick(_ => handleIgnore())
          )
        ),
        if (rowError.nonEmpty)
          mui.Typography(rowError).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "12px" })
        else Fragment()
      )
    )
  }
}
