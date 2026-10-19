package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{CreateRefundFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateMerchant, GetMerchants}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object BankRowCreateRefundForm {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      stmtId: UUID,
      rowId: UUID,
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ruleList: List[ImportRuleMatcher.Rule],
      ctx: AppContext,
      onSuccess: UUID => Unit,
      onCancel: () => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val matchedMerchant = RowRuleVerdict
      .ruleMerchantId(props.ruleList, props.row.description)
      .flatMap(mid => props.merchants.find(_.id == mid))

    val initMerchantName = matchedMerchant.map(_.name).getOrElse("")
    val initMerchantIdOpt = matchedMerchant.map(_.id)
    val initCategoryIdOpt = matchedMerchant.flatMap(_.categoryId)
    val initCategoryName =
      initCategoryIdOpt.flatMap(cid => props.categories.find(_.id == cid)).map(_.name).getOrElse("")

    val (amount, setAmount) = Hooks.useState(props.row.amount.abs.toString)
    val (date, setDate) = Hooks.useState(props.row.date.toString)
    val (notes, setNotes) = Hooks.useState(props.row.description)

    val (merchantInput, setMerchantInput) = Hooks.useState(initMerchantName)
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](initMerchantIdOpt)
    val (merchantOpen, setMerchantOpen) = Hooks.useState(false)

    val (categoryInput, setCategoryInput) = Hooks.useState(initCategoryName)
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](initCategoryIdOpt)
    val (categoryOpen, setCategoryOpen) = Hooks.useState(false)

    val (error, setError) = Hooks.useState("")

    val filteredMerchants = props.merchants
      .filter(m => merchantInput.isEmpty || m.name.toLowerCase.contains(merchantInput.toLowerCase))
      .take(6)

    val filteredCategories = props.categories
      .filter(c => categoryInput.isEmpty || c.name.toLowerCase.contains(categoryInput.toLowerCase))
      .take(6)

    def selectMerchant(m: GetMerchants.Response.Merchant): Unit = {
      setMerchantInput(m.name)
      setMerchantIdOpt(Some(m.id))
      setMerchantOpen(false)
      m.categoryId.foreach { cid =>
        setCategoryIdOpt(Some(cid))
        props.categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
      }
    }

    def handleSubmit(): Unit =
      (Try(BigDecimal(amount)).toOption, Try(LocalDate.parse(date)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date")
        case (Some(amt), Some(dt)) =>
          setError("")
          val merchantName = merchantInput.trim
          val categoryName = categoryInput.trim

          val fResult: Future[Unit] = for {
            resolvedCategoryId <- categoryName match {
              case "" => Future.successful[Option[UUID]](None)
              case _ =>
                categoryIdOpt match {
                  case Some(id) => Future.successful(Some(id))
                  case None =>
                    props.ctx.api.client
                      .createCategory(CreateCategory.Request(name = categoryName))
                      .map(r => Some(r.id))
                }
            }
            resolvedMerchantId <- merchantName match {
              case "" => Future.successful[Option[UUID]](None)
              case _ =>
                merchantIdOpt match {
                  case Some(id) => Future.successful(Some(id))
                  case None =>
                    props.ctx.api.client
                      .createMerchant(
                        CreateMerchant.Request(
                          name = merchantName,
                          notes = None,
                          categoryId = resolvedCategoryId,
                          taxId = None
                        )
                      )
                      .map(r => Some(r.id))
                }
            }
            resp <- props.ctx.api.client.createRefundFromRow(
              props.stmtId,
              props.rowId,
              CreateRefundFromRow.Request(
                amount = amt,
                date = dt,
                merchantId = resolvedMerchantId,
                categoryId = resolvedCategoryId,
                expenseId = None,
                notes = Some(notes).filter(_.nonEmpty)
              )
            )
          } yield props.onSuccess(resp.refundId)

          fResult.onComplete {
            case Success(_) => ()
            case Failure(ex) => setError(ex.getMessage)
          }
      }

    val dropdownItemSx = new CSSPropertiesUtils {
      padding = "8px 12px"; cursor = "pointer"; fontSize = "14px"
    }
    val dropdownPaperSx = new CSSPropertiesUtils {
      position = "absolute"; top = "100%"; left = "0"; right = "0"; zIndex = "1400"
      maxHeight = "200px"; overflowY = "auto"
      border = "1px solid var(--lc-surface-2)"; borderRadius = "4px"; backgroundColor = "var(--lc-paper)"
      boxShadow = "0 4px 12px rgba(0,0,0,0.1)"
    }

    val anyOpen = merchantOpen || categoryOpen

    form(onSubmit := { e =>
      e.preventDefault()
      handleSubmit()
    })(
      Fragment(
        if (anyOpen)
          (mui.Box
            .sx(new CSSPropertiesUtils {
              position = "fixed"; top = "0"; left = "0"; right = "0"; bottom = "0"; zIndex = "1399"
            })
            .onClick(_ => { setMerchantOpen(false); setCategoryOpen(false) })(): ReactElement)
        else Fragment(),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
          mui
            .Typography("New refund")
            .sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px"; userSelect = "none" }),
          if (error.nonEmpty)
            mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
          else Fragment(),
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "12px" })(
            mui.TextField
              .outlined()
              .label("Amount")
              .size("small")
              .value(amount)
              .onChange(e => setAmount(e.target.asInstanceOf[dom.HTMLInputElement].value))
              .sx(new CSSPropertiesUtils { flex = "1" }),
            mui.TextField
              .outlined()
              .label("Date")
              .size("small")
              .value(date)
              .onChange(e => setDate(e.target.asInstanceOf[dom.HTMLInputElement].value))
              .sx(new CSSPropertiesUtils { flex = "1" })
          ),
          mui.TextField
            .outlined()
            .label("Notes")
            .size("small")
            .value(notes)
            .onChange(e => setNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          // Merchant type-ahead
          mui.Box.sx(new CSSPropertiesUtils { position = "relative" })(
            mui.TextField
              .outlined()
              .label("Merchant (optional)")
              .size("small")
              .value(merchantInput)
              .onChange { e =>
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                setMerchantInput(v)
                setMerchantIdOpt(None)
                setMerchantOpen(true)
              }
              .sx(new CSSPropertiesUtils { width = "100%" }),
            if (merchantOpen && filteredMerchants.nonEmpty)
              (mui.Box.sx(dropdownPaperSx)(
                Fragment(
                  filteredMerchants.map { m =>
                    (mui.Box
                      .sx(dropdownItemSx)
                      .onClick { e =>
                        e.stopPropagation()
                        selectMerchant(m)
                      }(mui.Typography(m.name).sx(new CSSPropertiesUtils { fontSize = "14px" }))
                      .withKey(m.id.toString): ReactElement)
                  }*
                )
              ): ReactElement)
            else Fragment()
          ),
          // Category type-ahead
          mui.Box.sx(new CSSPropertiesUtils { position = "relative" })(
            mui.TextField
              .outlined()
              .label("Category (optional)")
              .size("small")
              .value(categoryInput)
              .onChange { e =>
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                setCategoryInput(v)
                setCategoryIdOpt(None)
                setCategoryOpen(true)
              }
              .sx(new CSSPropertiesUtils { width = "100%" }),
            if (categoryOpen && filteredCategories.nonEmpty)
              (mui.Box.sx(dropdownPaperSx)(
                Fragment(
                  filteredCategories.map { c =>
                    (mui.Box
                      .sx(dropdownItemSx)
                      .onClick { e =>
                        e.stopPropagation()
                        setCategoryInput(c.name)
                        setCategoryIdOpt(Some(c.id))
                        setCategoryOpen(false)
                      }(mui.Typography(c.name).sx(new CSSPropertiesUtils { fontSize = "14px" }))
                      .withKey(c.id.toString): ReactElement)
                  }*
                )
              ): ReactElement)
            else Fragment()
          ),
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
            mui.Button
              .normal()("Save")
              .variant("contained")
              .size("small")
              .`type`("submit")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
            mui.Button.normal()("Cancel").size("small").onClick(_ => props.onCancel())
          )
        )
      )
    )
  }
}
