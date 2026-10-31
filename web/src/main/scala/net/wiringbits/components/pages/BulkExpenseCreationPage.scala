package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetAllRows}
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateMerchant, GetMerchants}
import net.wiringbits.components.widgets.Loader
import net.wiringbits.core.SafeHooks
import net.wiringbits.models.{SaveState, User}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.compat.{MuiCompat, RouterCompat}
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success}

object BulkExpenseCreationPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage = component(Props(ctx, user))

  case class Props(ctx: AppContext, user: User)

  private[pages] val PageSize = 25

  private[pages] def normalize(s: String): String = s.toLowerCase.trim

  private[pages] def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val client = props.ctx.api.client

    AsyncComponent[(GetAllRows.Response, GetMerchants.Response, GetCategories.Response)](
      fetch = () =>
        for {
          rowsResp <- client.getAllBankStatementRows
          merchantsResp <- client.getMerchants
          catsResp <- client.getCategories
        } yield (rowsResp, merchantsResp, catsResp),
      render = { case (rowsResp, merchantsResp, catsResp) =>
        BulkExpenseCreationContent(props.ctx, rowsResp.data, merchantsResp.data, catsResp.data)
      },
      progressIndicator = () => Loader(props.ctx)
    )
  }
}

object BulkExpenseCreationContent {
  import BulkExpenseCreationPage.{PageSize, normalize, renderTextField}

  def apply(
      ctx: AppContext,
      rows: List[GetAllRows.RowData],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): KeyAddingStage =
    component(Props(ctx, rows, merchants, categories))

  case class Props(
      ctx: AppContext,
      rows: List[GetAllRows.RowData],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val merchants = props.merchants
    val categories = props.categories

    val history = useHistory()
    val location = RouterCompat.useTypedLocation()

    val descParam = {
      val search = location.search
      Option(new URLSearchParams(search).get("desc")).filter(_.nonEmpty)
    }

    val (allRows, setAllRows) = Hooks.useState(props.rows)

    // Screen 1
    val (listFilter, setListFilter) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)

    // Screen 2
    val (descFilter, setDescFilter) = Hooks.useState(descParam.getOrElse(""))
    val (selectedIds, setSelectedIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (merchantInput, setMerchantInput) = Hooks.useState("")
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (categoryInput, setCategoryInput) = Hooks.useState("")
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](None)
    val (notes, setNotes) = Hooks.useState("")
    val (save, setSave) = Hooks.useState[SaveState](SaveState.Idle)
    val saving = save.isSaving

    // Reset Screen 2 state when navigating to a description (or on mount with a desc in URL)
    SafeHooks.useEffect(descParam.getOrElse(""))(() => {
      val desc = descParam.getOrElse("")
      setDescFilter(desc)
      val initIds =
        if (desc.nonEmpty)
          allRows
            .filter(r => r.amount < 0 && r.expenseId.isEmpty && r.ignoredAt.isEmpty)
            .filter(r => normalize(r.description).contains(normalize(desc)))
            .map(_.id)
            .toSet
        else Set.empty[UUID]
      setSelectedIds(initIds)
      setSave {
        case SaveState.Failed(_) => SaveState.Idle
        case s => s
      }
      setNotes("")
      setMerchantInput("")
      setMerchantIdOpt(None)
      setCategoryInput("")
      setCategoryIdOpt(None)
    })

    val unprocessed = allRows.filter(r => r.amount < 0 && r.expenseId.isEmpty && r.ignoredAt.isEmpty)

    val filteredRows =
      if (descFilter.trim.isEmpty) Nil
      else unprocessed.filter(r => normalize(r.description).contains(normalize(descFilter))).sortBy(_.date.toString)

    val effectiveSelected = selectedIds.intersect(filteredRows.map(_.id).toSet)

    def resolveCatId(): Future[Option[UUID]] = categoryInput.trim match {
      case "" => Future.successful(None)
      case catName =>
        categoryIdOpt match {
          case Some(id) => Future.successful(Some(id))
          case None =>
            categories.find(_.name.equalsIgnoreCase(catName)) match {
              case Some(c) => Future.successful(Some(c.id))
              case None =>
                props.ctx.api.client.createCategory(CreateCategory.Request(name = catName)).map(r => Some(r.id))
            }
        }
    }

    def resolveMerchantId(catIdOpt: Option[UUID]): Future[Option[UUID]] = merchantInput.trim match {
      case "" => Future.successful(None)
      case merchantName =>
        merchantIdOpt match {
          case Some(id) => Future.successful(Some(id))
          case None =>
            merchants.find(_.name.equalsIgnoreCase(merchantName)) match {
              case Some(m) => Future.successful(Some(m.id))
              case None =>
                props.ctx.api.client
                  .createMerchant(CreateMerchant.Request(merchantName, None, catIdOpt, None))
                  .map(_.id)
                  .map(Some(_))
            }
        }
    }

    def handleImport(): Unit = {
      val ids = effectiveSelected.toList
      if (ids.isEmpty) { setSave(SaveState.Failed("No rows selected")) }
      else {
        setSave(SaveState.Saving)
        val notesVal = notes.trim

        (for {
          catIdOpt <- resolveCatId()
          midOpt <- resolveMerchantId(catIdOpt)
          processed <- ids.foldLeft(Future.successful(List.empty[UUID])) { (accF, rowId) =>
            accF.flatMap { acc =>
              filteredRows.find(_.id == rowId) match {
                case None => Future.successful(acc)
                case Some(row) =>
                  val req = CreateExpenseFromRow.Request(
                    amount = row.amount.abs,
                    date = row.date,
                    time = None,
                    merchantId = midOpt,
                    notes = Some(notesVal).filter(_.nonEmpty),
                    splits = catIdOpt.map(c => List(CreateExpenseFromRow.Split(c, row.amount.abs))).getOrElse(Nil),
                    receiptId = None
                  )
                  props.ctx.api.client.createExpenseFromRow(row.bankStatementId, rowId, req).map(_ => acc :+ rowId)
              }
            }
          }
        } yield processed).onComplete {
          case Success(processed) =>
            val done = processed.toSet
            setAllRows(cur => cur.map(r => if (done.contains(r.id)) r.copy(expenseId = Some(UUID.randomUUID())) else r))
            setSelectedIds(Set.empty)
            setSave(SaveState.Idle)
          case Failure(ex) =>
            setSave(SaveState.Failed(ex.getMessage))
        }
      }
    }

    def handleIgnore(): Unit = {
      val ids = effectiveSelected.toList
      if (ids.isEmpty) { setSave(SaveState.Failed("No rows selected")) }
      else {
        setSave(SaveState.Saving)

        ids
          .foldLeft(Future.successful(List.empty[UUID])) { (accF, rowId) =>
            accF.flatMap { acc =>
              filteredRows.find(_.id == rowId) match {
                case None => Future.successful(acc)
                case Some(row) =>
                  props.ctx.api.client.ignoreRow(row.bankStatementId, rowId).map(_ => acc :+ rowId)
              }
            }
          }
          .onComplete {
            case Success(ignored) =>
              val done = ignored.toSet
              setAllRows(cur => cur.map(r => if (done.contains(r.id)) r.copy(ignoredAt = Some(Instant.now())) else r))
              setSelectedIds(Set.empty)
              setSave(SaveState.Idle)
            case Failure(ex) =>
              setSave(SaveState.Failed(ex.getMessage))
          }
      }
    }

    val merchantAcProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
      options = merchants.toJSArray,
      renderInput = renderTextField("Merchant (optional)")
    )
    val merchantDyn = merchantAcProps.asInstanceOf[js.Dynamic]
    merchantDyn.freeSolo = true
    merchantDyn.size = "small"
    merchantDyn.disabled = saving
    merchantDyn.inputValue = merchantInput
    merchantDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.merchantOptionLabel(opt)
    merchantDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case m: GetMerchants.Response.Merchant =>
          setMerchantIdOpt(Some(m.id))
          setMerchantInput(m.name)
          m.categoryId.foreach { cid =>
            setCategoryIdOpt(Some(cid))
            categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
          }
        case _ => ()
      }
    merchantDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setMerchantInput(text)
      if (rsn != "reset") setMerchantIdOpt(None)
    }

    val categoryAcProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
      options = categories.toJSArray,
      renderInput = renderTextField("Category (optional)")
    )
    val categoryDyn = categoryAcProps.asInstanceOf[js.Dynamic]
    categoryDyn.freeSolo = true
    categoryDyn.size = "small"
    categoryDyn.disabled = saving
    categoryDyn.inputValue = categoryInput
    categoryDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.categoryOptionLabel(opt)
    categoryDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case c: GetCategories.Response.Category => setCategoryIdOpt(Some(c.id)); setCategoryInput(c.name)
        case _ => ()
      }
    categoryDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setCategoryInput(text)
      if (rsn != "reset") setCategoryIdOpt(None)
    }

    val hcell = new CSSPropertiesUtils {
      fontWeight = "700"; fontSize = "11px"; letterSpacing = "0.06em"
      textTransform = "uppercase"; color = "var(--lc-text-2)"
    }

    descParam match {

      case None =>
        // ── Screen 1: description list ──
        val groups = unprocessed
          .groupBy(_.description)
          .view
          .mapValues(_.sortBy(_.date))
          .toList
          .filter { case (desc, _) =>
            listFilter.trim.isEmpty || normalize(desc).contains(normalize(listFilter))
          }
          .sortBy(-_._2.size)

        val totalPages = math.max(1, math.ceil(groups.size.toDouble / PageSize).toInt)
        val safePage = math.min(page, math.max(0, totalPages - 1))
        val pagedGroups = groups.slice(safePage * PageSize, (safePage + 1) * PageSize)

        mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "20px"
          })(
            mui
              .Typography("Bulk expense creation")
              .sx(new CSSPropertiesUtils { fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)" }),
            mui
              .Typography(s"${groups.size} descriptions, ${unprocessed.size} rows")
              .sx(new CSSPropertiesUtils { fontSize = "14px"; color = "var(--lc-text-2)" }),
            mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
            mui.Button
              .normal()("← Back")
              .size("small")
              .variant("outlined")
              .onClick(_ => history.push("/bank-statements"))
          ),
          mui.TextField
            .outlined()
            .label("Filter descriptions")
            .size("small")
            .value(listFilter)
            .onChange { e =>
              setListFilter(e.target.asInstanceOf[dom.HTMLInputElement].value)
              setPage(0)
            }
            .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "16px" }),
          if (groups.isEmpty)
            mui.Box.sx(new CSSPropertiesUtils {
              padding = "48px"; textAlign = "center"
              border = "1px solid var(--lc-border)"; borderRadius = "8px"
            })(
              mui
                .Typography("No unprocessed rows match.")
                .sx(new CSSPropertiesUtils { fontSize = "16px"; color = "var(--lc-text-2)" })
            )
          else
            Fragment(
              Fragment(
                pagedGroups.map { case (desc, rows) =>
                  (mui.Box
                    .sx(new CSSPropertiesUtils {
                      border = "1px solid var(--lc-border)"; borderRadius = "8px"; padding = "12px 16px"
                      marginBottom = "8px"; backgroundColor = "var(--lc-paper)"; cursor = "pointer"
                      display = "flex"; alignItems = "center"; gap = "12px"
                    })
                    .onClick { _ =>
                      val encoded = js.Dynamic.global.encodeURIComponent(desc).asInstanceOf[String]
                      history.push(s"/bank-statements/bulk-expenses?desc=$encoded")
                    }(
                      mui
                        .Typography(desc)
                        .sx(new CSSPropertiesUtils {
                          fontFamily = "\"IBM Plex Mono\", monospace"; fontSize = "13px"
                          fontWeight = "600"; color = "var(--lc-text)"; wordBreak = "break-all"; flex = "1"
                        }),
                      mui.Chip
                        .label(s"${rows.size}")
                        .size("small")
                        .sx(new CSSPropertiesUtils {
                          height = "20px"; fontSize = "11px"
                          backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
                        })
                    )
                    .withKey(desc): ReactElement)
                }*
              ),
              mui.Box.sx(new CSSPropertiesUtils { marginTop = "16px" })(
                mui.Pagination
                  .withProps(MuiCompat.paginationProps(count = totalPages, page = safePage, onChange = setPage))
              )
            )
        )

      case Some(_) =>
        // ── Screen 2: row selector ──
        val allSelected = filteredRows.nonEmpty && filteredRows.forall(r => effectiveSelected.contains(r.id))
        val someSelected = filteredRows.exists(r => effectiveSelected.contains(r.id)) && !allSelected

        mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; alignItems = "center"; gap = "12px"; marginBottom = "20px"
          })(
            mui.Button
              .normal()("← Back")
              .size("small")
              .variant("outlined")
              .onClick(_ => history.push("/bank-statements/bulk-expenses")),
            mui
              .Typography("Bulk expense creation")
              .sx(new CSSPropertiesUtils { fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)" })
          ),
          mui.TextField
            .outlined()
            .label("Description filter")
            .size("small")
            .value(descFilter)
            .onChange(e => setDescFilter(e.target.asInstanceOf[dom.HTMLInputElement].value))
            .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "8px" }),
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; alignItems = "center"; gap = "8px"; marginBottom = "8px"
          })(
            mui
              .Typography(s"${filteredRows.size} rows")
              .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)"; flex = "1" }),
            mui.Button
              .normal()("Select all")
              .size("small")
              .disabled(allSelected || filteredRows.isEmpty)
              .onClick(_ => setSelectedIds(cur => cur ++ filteredRows.map(_.id).toSet)),
            mui.Button
              .normal()("Deselect all")
              .size("small")
              .disabled(effectiveSelected.isEmpty)
              .onClick(_ => setSelectedIds(Set.empty))
          ),
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell.padding("checkbox")(
                  (mui.Checkbox
                    .checked(allSelected)
                    .indeterminate(someSelected)
                    .size("small")
                    .onChange((_, checked) =>
                      if (checked) setSelectedIds(cur => cur ++ filteredRows.map(_.id).toSet)
                      else setSelectedIds(cur => cur -- filteredRows.map(_.id).toSet)
                    ): ReactElement)
                ),
                mui.TableCell.sx(hcell)("Date"),
                mui.TableCell.align("right").sx(hcell)("Amount"),
                mui.TableCell.sx(hcell)("Description")
              )
            ),
            if (filteredRows.isEmpty)
              mui.TableBody(
                mui.TableRow(
                  mui.TableCell.colSpan(4)(
                    mui
                      .Typography("No rows match the filter.")
                      .sx(new CSSPropertiesUtils {
                        padding = "24px 0"; textAlign = "center"
                        color = "var(--lc-text-2)"; display = "block"
                      })
                  )
                )
              )
            else
              mui.TableBody(
                Fragment(
                  filteredRows.map { row =>
                    val isSelected = effectiveSelected.contains(row.id)
                    (mui.TableRow
                      .hover(true)
                      .selected(isSelected)(
                        mui.TableCell.padding("checkbox")(
                          (mui.Checkbox
                            .checked(isSelected)
                            .size("small")
                            .onChange((_, checked) =>
                              if (checked) setSelectedIds(cur => cur + row.id)
                              else setSelectedIds(cur => cur - row.id)
                            ): ReactElement)
                        ),
                        mui.TableCell.sx(new CSSPropertiesUtils {
                          fontFamily = "\"IBM Plex Mono\", monospace"
                          fontSize = "13px"; color = "var(--lc-text-2)"; whiteSpace = "nowrap"
                        })(row.date.toString),
                        mui.TableCell.align("right")(
                          mui
                            .Typography(s"-${row.amount.abs}")
                            .sx(new CSSPropertiesUtils {
                              fontWeight = "700"; fontSize = "13px"; color = "var(--lc-error)"
                              fontFamily = "\"IBM Plex Mono\", monospace"
                            })
                        ),
                        mui.TableCell.sx(new CSSPropertiesUtils { maxWidth = "400px" })(
                          mui
                            .Typography(row.description)
                            .sx(new CSSPropertiesUtils {
                              fontSize = "13px"; fontFamily = "\"IBM Plex Mono\", monospace"
                              overflow = "hidden"; textOverflow = "ellipsis"
                              whiteSpace = "nowrap"; display = "block"
                            })
                        )
                      )
                      .withKey(row.id.toString): ReactElement)
                  }*
                )
              )
          ),
          mui.Box.sx(new CSSPropertiesUtils {
            marginTop = "20px"; padding = "16px"
            border = "1px solid var(--lc-border)"; borderRadius = "8px"
            display = "flex"; flexDirection = "column"; gap = "12px"
          })(
            mui
              .Typography(s"${effectiveSelected.size} rows selected")
              .sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px" }),
            save.errorOpt match {
              case Some(msg) =>
                mui.Typography(msg).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
              case None => Fragment()
            },
            (mui.Autocomplete.withProps(merchantAcProps).withKey("m"): ReactElement),
            (mui.Autocomplete.withProps(categoryAcProps).withKey("c"): ReactElement),
            mui.TextField
              .outlined()
              .label("Notes (applied to all)")
              .size("small")
              .value(notes)
              .disabled(saving)
              .onChange(e => setNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
              mui.Button
                .normal()(if (saving) "Processing…" else s"Import ${effectiveSelected.size} selected")
                .size("small")
                .variant("contained")
                .disabled(saving || effectiveSelected.isEmpty)
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                .onClick(_ => handleImport()),
              mui.Button
                .normal()(s"Ignore ${effectiveSelected.size} selected")
                .size("small")
                .disabled(saving || effectiveSelected.isEmpty)
                .onClick(_ => handleIgnore())
            )
          )
        )
    }
  }
}
