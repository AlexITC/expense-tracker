package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.merchants.{GetMerchants, GetPendingMerchants, MarkMerchantAsDuplicate, ReviewMerchant}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success}

sealed trait MerchantFilter
object MerchantFilter {
  case object Pending extends MerchantFilter
  case object Confirmed extends MerchantFilter
  case object All extends MerchantFilter
}

object MerchantReviewPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (pending, setPending) = Hooks.useState[List[GetPendingMerchants.Merchant]](Nil)
    val (allMerchants, setAllMerchants) = Hooks.useState[List[GetMerchants.Response.Merchant]](Nil)
    val (receiptMap, setReceiptMap) =
      Hooks.useState[Map[UUID, (UUID, String)]](Map.empty)
    val (loading, setLoading) = Hooks.useState(true)
    val (filter, setFilter) = Hooks.useState[MerchantFilter](MerchantFilter.Pending)
    val (searchText, setSearchText) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)
    val pageSize = 15

    Hooks.useEffect(
      () => {
        setLoading(true)
        for {
          p <- props.ctx.api.client.getPendingMerchants
          m <- props.ctx.api.client.getMerchants
          r <- props.ctx.api.client.getReceipts
        } yield {
          setPending(p.data)
          setAllMerchants(m.data)

          val map: Map[UUID, (UUID, String)] = r.data
            .flatMap(rec => rec.merchantId.map(mId => mId -> (rec.id, rec.documentFilename)))
            .foldLeft(Map.empty[UUID, (UUID, String)]) { case (acc, (mId, v)) =>
              if (acc.contains(mId)) acc else acc + (mId -> v)
            }

          setReceiptMap(map)
          setLoading(false)
        }
        ()
      },
      js.Array[Any]()
    )

    def onConfirmed(id: UUID, newName: String): Unit = {
      setPending(cur => cur.filterNot(_.id == id))
      setAllMerchants(cur => cur.map(m => if (m.id == id) m.copy(name = newName, reviewed = true) else m))
    }

    def onRemoved(id: UUID): Unit = {
      setPending(cur => cur.filterNot(_.id == id))
      setAllMerchants(cur => cur.filterNot(_.id == id))
    }

    def paginationWidget(totalPages: Int, currentPage: Int): ReactElement =
      if (totalPages <= 1) Fragment()
      else {
        val pProps = MuiCompat.paginationProps(
          count = totalPages,
          page = currentPage,
          onChange = setPage,
          size = Some("small")
        )
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; justifyContent = "center"; padding = "12px 0" })(
          mui.Pagination.withProps(pProps)
        )
      }

    def filterButton(label: String, f: MerchantFilter): ReactElement =
      mui.Button
        .normal()(label)
        .variant(if (filter == f) "contained" else "outlined")
        .size("small")
        .sx(if (filter == f) new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }
        else new CSSPropertiesUtils {})
        .onClick(_ => { setFilter(f); setPage(0) })

    if (loading) {
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px" })(mui.CircularProgress())
    } else {
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "800px" })(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px"; marginBottom = "12px" })(
          filterButton("Pending", MerchantFilter.Pending),
          filterButton("Confirmed", MerchantFilter.Confirmed),
          filterButton("All", MerchantFilter.All)
        ),
        mui.TextField
          .outlined()
          .label("Search merchants")
          .size("small")
          .value(searchText)
          .onChange(e => { setSearchText(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
          .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "16px" }),
        filter match {
          case MerchantFilter.Pending =>
            val filtered =
              if (searchText.isEmpty) pending
              else pending.filter(_.name.toLowerCase.contains(searchText.toLowerCase))
            val totalPages = math.ceil(filtered.size.toDouble / pageSize).toInt
            val currentPage = math.min(page, math.max(0, totalPages - 1))
            val paged = filtered.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
            if (filtered.isEmpty)
              mui
                .Typography("No pending merchants to review.")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
            else
              Fragment(
                Fragment(
                  paged.map { m =>
                    PendingMerchantItem(
                      props.ctx,
                      m,
                      allMerchants,
                      receiptMap.get(m.id),
                      onConfirmed = (newName: String) => onConfirmed(m.id, newName),
                      onRemoved = () => onRemoved(m.id)
                    )
                      .withKey(m.id.toString)
                  }*
                ),
                paginationWidget(totalPages, currentPage)
              )

          case MerchantFilter.Confirmed =>
            val confirmed = allMerchants.filter(_.reviewed)
            val filtered =
              if (searchText.isEmpty) confirmed
              else confirmed.filter(_.name.toLowerCase.contains(searchText.toLowerCase))
            val totalPages = math.ceil(filtered.size.toDouble / pageSize).toInt
            val currentPage = math.min(page, math.max(0, totalPages - 1))
            val paged = filtered.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
            if (filtered.isEmpty)
              mui
                .Typography("No confirmed merchants.")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
            else
              Fragment(
                Fragment(paged.map(m => ConfirmedMerchantRow(m).withKey(m.id.toString))*),
                paginationWidget(totalPages, currentPage)
              )

          case MerchantFilter.All =>
            val filtered =
              if (searchText.isEmpty) allMerchants
              else allMerchants.filter(_.name.toLowerCase.contains(searchText.toLowerCase))
            val totalPages = math.ceil(filtered.size.toDouble / pageSize).toInt
            val currentPage = math.min(page, math.max(0, totalPages - 1))
            val paged = filtered.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
            if (filtered.isEmpty)
              mui
                .Typography("No merchants.")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
            else
              Fragment(
                Fragment(paged.map(m => ConfirmedMerchantRow(m).withKey(m.id.toString))*),
                paginationWidget(totalPages, currentPage)
              )
        }
      )
    }
  }
}

object ConfirmedMerchantRow {
  def apply(merchant: GetMerchants.Response.Merchant): KeyAddingStage =
    component(merchant)

  val component: FunctionalComponent[GetMerchants.Response.Merchant] =
    FunctionalComponent[GetMerchants.Response.Merchant] { merchant =>
      mui.Box.sx(new CSSPropertiesUtils {
        padding = "10px 16px"
        marginBottom = "6px"
        border = "1px solid var(--lc-border)"
        borderRadius = "8px"
        backgroundColor = "var(--lc-paper)"
        display = "flex"
        alignItems = "center"
        gap = "8px"
      })(
        mui
          .Typography(merchant.name)
          .sx(new CSSPropertiesUtils {
            fontSize = "14px"
            fontWeight = "600"
            color = "var(--lc-text)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          }),
        if (!merchant.reviewed)
          mui
            .Typography("pending")
            .sx(new CSSPropertiesUtils {
              fontSize = "11px"
              color = "var(--lc-text-2)"
              border = "1px solid var(--lc-border)"
              borderRadius = "4px"
              padding = "1px 6px"
            })
        else Fragment()
      )
    }
}

object PendingMerchantItem {
  def apply(
      ctx: AppContext,
      merchant: GetPendingMerchants.Merchant,
      allMerchants: List[GetMerchants.Response.Merchant],
      firstReceipt: Option[(UUID, String)],
      onConfirmed: String => Unit,
      onRemoved: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, merchant, allMerchants, firstReceipt, onConfirmed, onRemoved))

  case class Props(
      ctx: AppContext,
      merchant: GetPendingMerchants.Merchant,
      allMerchants: List[GetMerchants.Response.Merchant],
      firstReceipt: Option[(UUID, String)],
      onConfirmed: String => Unit,
      onRemoved: () => Unit
  )

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined.withProps(MuiCompat.textFieldProps(params, label)): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (editName, setEditName) = Hooks.useState(props.merchant.name)
    val (duplicateTarget, setDuplicateTarget) = Hooks.useState("")
    val (working, setWorking) = Hooks.useState(false)
    val (itemError, setItemError) = Hooks.useState("")

    val realMerchants = props.allMerchants.filterNot(_.id == props.merchant.id)

    def handleConfirm(): Unit = {
      val trimmed = editName.trim
      if (trimmed.nonEmpty) {
        setWorking(true)
        setItemError("")
        val nameOpt = Some(trimmed).filter(_ != props.merchant.name)
        props.ctx.api.client
          .reviewMerchant(props.merchant.id, ReviewMerchant.Request(name = nameOpt, categoryId = None))
          .onComplete {
            case Success(_) => props.onConfirmed(trimmed)
            case Failure(ex) =>
              setItemError(ex.getMessage)
              setWorking(false)
          }
      }
    }

    def handleDiscard(): Unit = {
      setWorking(true)
      setItemError("")
      props.ctx.api.client
        .deleteMerchant(props.merchant.id)
        .onComplete {
          case Success(_) => props.onRemoved()
          case Failure(ex) =>
            setItemError(ex.getMessage)
            setWorking(false)
        }
    }

    def handleDuplicateChange(targetId: String): Unit =
      if (targetId.nonEmpty) {
        setDuplicateTarget(targetId)
        setWorking(true)
        setItemError("")
        props.ctx.api.client
          .markMerchantAsDuplicate(props.merchant.id, MarkMerchantAsDuplicate.Request(UUID.fromString(targetId)))
          .onComplete {
            case Success(_) => props.onRemoved()
            case Failure(ex) =>
              setDuplicateTarget("")
              setItemError(ex.getMessage)
              setWorking(false)
          }
      }

    def handleViewTicket(receiptId: UUID, filename: String): Unit = {
      val mimeType = filename.toLowerCase match {
        case f if f.endsWith(".pdf") => "application/pdf"
        case f if f.endsWith(".jpg") || f.endsWith(".jpeg") => "image/jpeg"
        case f if f.endsWith(".png") => "image/png"
        case _ => "application/octet-stream"
      }
      props.ctx.api.client.getReceiptFile(receiptId).onComplete {
        case Success((bytes, _, _)) =>
          val typedArray = new js.typedarray.Int8Array(bytes.length)
          bytes.indices.foreach(i => typedArray(i) = bytes(i))
          val blob = new dom.Blob(
            js.Array(typedArray.buffer.asInstanceOf[js.typedarray.ArrayBuffer]),
            new dom.BlobPropertyBag { `type` = mimeType }
          )
          val url = dom.URL.createObjectURL(blob)
          dom.window.open(url, "_blank")
        case Failure(ex) => setItemError(ex.getMessage)
      }
    }

    val ticketSection: ReactElement = props.firstReceipt match {
      case Some((receiptId, filename)) =>
        mui.Button
          .normal()("View ticket")
          .size("small")
          .variant("outlined")
          .onClick(_ => handleViewTicket(receiptId, filename))
      case None =>
        mui
          .Typography("no ticket")
          .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "12px" })
    }

    mui.Box.sx(new CSSPropertiesUtils {
      border = "1px solid var(--lc-border)"
      borderRadius = "8px"
      backgroundColor = "var(--lc-paper)"
      padding = "16px"
      marginBottom = "8px"
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; gap = "8px"; marginBottom = "12px"
      })(
        mui
          .Typography(s"Pending: ${props.merchant.name}")
          .sx(new CSSPropertiesUtils {
            fontWeight = "600"
            fontSize = "15px"
            color = "var(--lc-text)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          }),
        ticketSection
      ),
      form(onSubmit := { e =>
        e.preventDefault()
        handleConfirm()
      })(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; flexWrap = "wrap"; gap = "8px"; alignItems = "center"; marginBottom = "8px"
        })(
          mui.TextField
            .outlined()
            .label("Canonical name")
            .value(editName)
            .size("small")
            .disabled(working)
            .onChange(e => setEditName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          mui.Button
            .normal()("Confirm")
            .variant("contained")
            .size("small")
            .`type`("submit")
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
            .disabled(working || editName.trim.isEmpty),
          mui.Button
            .normal()("Discard")
            .variant("outlined")
            .size("small")
            .color("error")
            .disabled(working)
            .onClick(_ => handleDiscard())
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexWrap = "wrap"; gap = "8px"; alignItems = "center" })(
        {
          val acProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
            options = realMerchants.toJSArray,
            renderInput = renderTextField("Mark as duplicate of…")
          )
          val acDyn = acProps.asInstanceOf[js.Dynamic]
          acDyn.size = "small"
          acDyn.disabled = working
          acDyn.value = realMerchants
            .find(_.id.toString == duplicateTarget)
            .map(_.asInstanceOf[js.Any])
            .getOrElse(js.undefined.asInstanceOf[js.Any])
          acDyn.getOptionLabel = (opt: js.Any) =>
            if (opt == null || js.isUndefined(opt)) ""
            else opt.asInstanceOf[js.Dynamic].name.asInstanceOf[String]
          acDyn.isOptionEqualToValue = (opt: js.Any, v: js.Any) =>
            opt.asInstanceOf[js.Dynamic].id.toString == v.asInstanceOf[js.Dynamic].id.toString
          acDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
            (value: Any) match {
              case m: GetMerchants.Response.Merchant => handleDuplicateChange(m.id.toString)
              case _ => setDuplicateTarget("")
            }
          (mui.Autocomplete.withProps(acProps).withKey(s"dup-ac-${props.merchant.id}"): ReactElement)
        }
      ),
      if (itemError.nonEmpty)
        mui
          .Typography(itemError)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginTop = "8px" })
      else Fragment()
    )
  }
}
