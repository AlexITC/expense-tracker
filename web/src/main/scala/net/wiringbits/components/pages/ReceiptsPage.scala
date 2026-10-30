package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.expenses.{CreateExpense, GetExpenses}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.bankstatements.GetAllRows
import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleAction}
import net.wiringbits.api.models.receipts.{ConfirmReceipt, GetReceipt, GetReceipts, LinkReceiptToExpense}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.ReceiptsLogic.{BatchState, ConfirmForm, ReceiptMode}
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{alt, checked, form, iframe, img, input, multiple, onChange, onSubmit, sandbox, src, `type`}

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success, Try}

object ReceiptsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { height = "100%"; display = "flex"; flexDirection = "column" })(
      AsyncComponent[GetReceipts.Response](
        fetch = () => props.ctx.api.client.getReceipts,
        render = receipts =>
          AsyncComponent[GetMerchants.Response](
            fetch = () => props.ctx.api.client.getMerchants,
            render = merchants =>
              AsyncComponent[GetCategories.Response](
                fetch = () => props.ctx.api.client.getCategories,
                render = categories => ReceiptList(props.ctx, receipts, merchants, categories, () => forceRefresh()),
                progressIndicator = () => Loader(props.ctx),
                watchedObjects = List(timesRefreshing)
              ),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object ReceiptList {
  def apply(
      ctx: AppContext,
      receipts: GetReceipts.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, receipts, merchants, categories, onRefresh))

  case class Props(
      ctx: AppContext,
      receipts: GetReceipts.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  )

  private val FilterNeedsConfirm = "needs_confirm"
  private val FilterProcessing = "processing"
  private val FilterConfirmed = "confirmed"
  private val FilterIgnored = "ignored"
  private val FilterAll = "all"

  private val secondaryStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-text-2)" }
  private val parsedStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-brand)" }
  private val linkedStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-link)" }
  private val remainderStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-text-2)" }
  private def mimeForFilename(filename: String): String = filename.toLowerCase match {
    case f if f.endsWith(".pdf") => "application/pdf"
    case f if f.endsWith(".jpg") || f.endsWith(".jpeg") => "image/jpeg"
    case f if f.endsWith(".png") => "image/png"
    case _ => "application/octet-stream"
  }

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "12px 16px"
    width = "100%"
  }

  private def toListReceipt(r: GetReceipt.Response): GetReceipts.Response.Receipt =
    GetReceipts.Response.Receipt(
      id = r.id,
      documentFilename = r.documentFilename,
      documentStatus = r.documentStatus,
      amount = r.amount,
      date = r.date,
      merchantName = r.merchantName,
      taxId = r.taxId,
      expenseId = r.expenseId,
      ignoredAt = r.ignoredAt,
      createdAt = r.createdAt
    )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (uploadKind, setUploadKind) = Hooks.useState("Receipt")
    val (error, setError) = Hooks.useState("")
    val (toast, setToast) = Hooks.useState[Option[String]](None)

    // batch upload progress: (done, total, failed) + per-file results
    val (batch, setBatch) = Hooks.useState[BatchState](BatchState.Idle)

    // polling spinner: IDs of receipts currently being fetched
    val (pollingIds, setPollingIds) = Hooks.useState[Set[UUID]](Set.empty)

    // all expenses for linked-evidence display
    val (allExpenses, setAllExpenses) = Hooks.useState[List[GetExpenses.Response.Expense]](Nil)

    // selected receipt, plus its create-expense form or link picker
    val (mode, setMode) = Hooks.useState[ReceiptMode](ReceiptMode.Viewing(None))
    def updateForm(f: ConfirmForm => ConfirmForm): Unit = setMode(_.withForm(f))

    // link-existing state
    val (expenseList, setExpenseList) = Hooks.useState[List[GetExpenses.Response.Expense]](Nil)
    val (expenseFilter, setExpenseFilter) = Hooks.useState("")

    // row cross-link state (for confirm form)
    val (rowList, setRowList) = Hooks.useState[List[GetAllRows.RowData]](Nil)

    // batch confirm state
    val (batchConfirmMode, setBatchConfirmMode) = Hooks.useState(false)
    // receiptId → resolved merchantId (None means resolution returned no match)
    val (resolvedMerchantIds, setResolvedMerchantIds) = Hooks.useState[Map[UUID, Option[UUID]]](Map.empty)
    val (batchConfirmSelectedIds, setBatchConfirmSelectedIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (batchConfirm, setBatchConfirm) = Hooks.useState[BatchState](BatchState.Idle)

    val (ruleList, setRuleList) = Hooks.useState[List[ImportRuleMatcher.Rule]](Nil)
    // Explicit rules plus one virtual rule per merchant name; `ruleList` itself stays explicit-only.
    val effectiveRuleList: List[ImportRuleMatcher.Rule] = Hooks.useMemo(
      () => ImportRuleMatcher.effectiveRules(ruleList, props.merchants.data.map(m => m.id -> m.name)),
      List(ruleList, props.merchants.data)
    )
    val (showIgnored, setShowIgnored) = Hooks.useState(false)
    val (receiptFilter, setReceiptFilter) = Hooks.useState(FilterNeedsConfirm)
    val (receiptPage, setReceiptPage) = Hooks.useState(0)
    val (previewUrl, setPreviewUrl) = Hooks.useState[Option[String]](None)
    val (previewLoading, setPreviewLoading) = Hooks.useState(false)

    val (localReceipts, setLocalReceipts) = Hooks.useState(props.receipts.data)
    val receiptsKey =
      props.receipts.data.map(r => s"${r.id}:${r.documentStatus.entryName}:${r.expenseId}").mkString("|")
    Hooks.useEffect(
      () => { setLocalReceipts(props.receipts.data); () },
      js.Array(receiptsKey)
    )

    val pendingReceipts =
      localReceipts.filter(r =>
        r.documentStatus == DocumentStatus.Ingested || r.documentStatus == DocumentStatus.Processing || r.documentStatus == DocumentStatus.Analyzed
      )
    val pendingKey = pendingReceipts.map(_.id.toString).sorted.mkString(",")
    Hooks.useEffect(
      () => {
        if (pendingReceipts.nonEmpty) {
          val timer = dom.window.setInterval(
            () =>
              pendingReceipts.foreach { pending =>
                setPollingIds(cur => cur + pending.id)
                props.ctx.api.client.getReceipt(pending.id).onComplete {
                  case Success(updated) =>
                    setPollingIds(cur => cur - updated.id)
                    setLocalReceipts(cur => cur.map(r => if (r.id == updated.id) toListReceipt(updated) else r))
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

    // Fetch all expenses and import rules once on mount
    Hooks.useEffect(
      () => {
        props.ctx.api.client.getExpenses.onComplete {
          case Success(resp) => setAllExpenses(resp.data)
          case Failure(_) => ()
        }
        props.ctx.api.client.getImportRules.onComplete {
          case Success(resp) =>
            setRuleList(
              resp.data.map(ImportRuleMatcher.Rule.fromApi)
            )
          case Failure(_) => ()
        }
        ()
      },
      js.Array()
    )

    // Auto-select first visible receipt when filter or list changes
    val visibleForFilter = localReceipts
      .filter { r =>
        receiptFilter match {
          case FilterNeedsConfirm =>
            r.documentStatus == DocumentStatus.Parsed && r.expenseId.isEmpty && r.ignoredAt.isEmpty
          case FilterProcessing =>
            r.documentStatus == DocumentStatus.Processing || r.documentStatus == DocumentStatus.Analyzed || r.documentStatus == DocumentStatus.Ingested
          case FilterConfirmed => r.expenseId.isDefined
          case FilterIgnored => r.ignoredAt.isDefined
          case _ => true
        }
      }
      .sortBy(_.createdAt)(using Ordering[Instant].reverse)
    Hooks.useEffect(
      () => {
        setMode(_.keptVisible(visibleForFilter.map(_.id)))
        ()
      },
      js.Array(visibleForFilter.map(_.id.toString).mkString(","), receiptFilter)
    )

    val selectedReceiptId = mode.selectedId
    val selectedIdKey = selectedReceiptId.map(_.toString).getOrElse("")
    Hooks.useEffect(
      () => {
        var cancelled = false
        var blobUrl = Option.empty[String]
        selectedReceiptId.foreach { id =>
          localReceipts.find(_.id == id).foreach { r =>
            val mimeType = mimeForFilename(r.documentFilename)
            if (mimeType != "application/octet-stream") {
              setPreviewLoading(true)
              props.ctx.api.client.getReceiptFile(id).onComplete {
                case Success((bytes, _, _)) if !cancelled =>
                  val typedArray = new js.typedarray.Int8Array(bytes.length)
                  bytes.indices.foreach(i => typedArray(i) = bytes(i))
                  val blob = new dom.Blob(
                    js.Array(typedArray.buffer.asInstanceOf[js.typedarray.ArrayBuffer]),
                    new dom.BlobPropertyBag { `type` = mimeType }
                  )
                  val url = dom.URL.createObjectURL(blob)
                  blobUrl = Some(url)
                  setPreviewLoading(false)
                  setPreviewUrl(Some(url))
                case Failure(ex) if !cancelled =>
                  setPreviewLoading(false)
                  setError(ex.getMessage)
                case _ => ()
              }
            }
          }
        }
        () => {
          cancelled = true; setPreviewLoading(false); setPreviewUrl(None); blobUrl.foreach(dom.URL.revokeObjectURL); ()
        }
      },
      js.Array(selectedIdKey)
    )

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    def handleViewTicket(receiptId: UUID, filename: String): Unit = {
      val mimeType = mimeForFilename(filename)
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
        case Failure(ex) => setError(ex.getMessage)
      }
    }

    def handleFilesBatch(files: List[dom.File]): Unit =
      if (files.nonEmpty) {
        val total = files.length
        setBatch(BatchState.Running(0, total, 0, Nil))
        setError("")
        def loop(remaining: List[dom.File]): Unit =
          remaining match {
            case Nil => setBatch(_.finished)
            case file :: rest =>
              val reader = new dom.FileReader()
              reader.readAsArrayBuffer(file)
              reader.onload = { _ =>
                val buffer = reader.result.asInstanceOf[js.typedarray.ArrayBuffer]
                val bytes = new js.typedarray.Int8Array(buffer).toArray
                props.ctx.api.client.uploadReceipt(bytes, uploadKind, file.name).onComplete {
                  case Success(resp) =>
                    if (localReceipts.exists(_.id == resp.id)) {
                      setBatch(_.recorded(file.name, Left("Already uploaded (duplicate)")))
                      loop(rest)
                    } else {
                      props.ctx.api.client.getReceipt(resp.id).onComplete {
                        case Success(r) => setLocalReceipts(cur => toListReceipt(r) :: cur)
                        case Failure(_) => ()
                      }
                      setBatch(_.recorded(file.name, Right(())))
                      loop(rest)
                    }
                  case Failure(ex) =>
                    setBatch(_.recorded(file.name, Left(ex.getMessage)))
                    loop(rest)
                }
              }
              reader.onerror = { _ =>
                setBatch(_.recorded(file.name, Left("File read error")))
                loop(rest)
              }
          }
        loop(files)
      }

    def handleFileChange(inputEl: dom.HTMLInputElement): Unit = {
      val fileList = inputEl.files
      inputEl.value = ""
      val files = (0 until fileList.length).map(fileList(_)).toList
      handleFilesBatch(files)
    }

    def handleDelete(receiptId: UUID): Unit =
      props.ctx.api.client
        .deleteReceipt(receiptId)
        .onComplete {
          case Success(_) => setLocalReceipts(cur => cur.filterNot(_.id == receiptId))
          case Failure(ex) => setError(ex.getMessage)
        }

    def merchantDefaultSplits(merchantIdStr: String, amount: BigDecimal): List[CreateExpense.Split] =
      Try(UUID.fromString(merchantIdStr)).toOption
        .flatMap(id => props.merchants.data.find(_.id == id))
        .flatMap(_.categoryId)
        .map(catId => List(CreateExpense.Split(catId, amount)))
        .getOrElse(Nil)

    def handleConfirm(receiptId: UUID, form: ConfirmForm): Unit =
      (Try(BigDecimal(form.amount)).toOption, Try(LocalDate.parse(form.date)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date (use YYYY-MM-DD)")
        case (Some(amount), Some(date)) =>
          val time = if (form.time.isEmpty) None else Try(LocalTime.parse(form.time)).toOption
          val merchantId = if (form.merchantId.isEmpty) None else Try(UUID.fromString(form.merchantId)).toOption
          val notes = Option(form.notes.trim).filter(_.nonEmpty)
          val explicit = form.splits.flatMap { case (catId, _, amt) =>
            if (catId.nonEmpty && amt.nonEmpty)
              Try(CreateExpense.Split(UUID.fromString(catId), BigDecimal(amt))).toOption
            else None
          }
          val splits = if (explicit.nonEmpty) explicit else merchantDefaultSplits(form.merchantId, amount)
          props.ctx.api.client
            .confirmReceipt(
              receiptId,
              ConfirmReceipt
                .Request(
                  Some(amount),
                  Some(date),
                  time,
                  merchantId,
                  notes,
                  splits,
                  form.parsedMerchantName,
                  form.rowId
                )
            )
            .onComplete {
              case Success(_) =>
                setMode(_.confirmed(receiptId))
                setError("")
                showToast("Expense created")
                props.ctx.api.client.getReceipt(receiptId).onComplete {
                  case Success(updated) =>
                    setLocalReceipts(cur => cur.map(r => if (r.id == updated.id) toListReceipt(updated) else r))
                  case Failure(_) => ()
                }
              case Failure(ex) => setError(ex.getMessage)
            }
      }

    def openLinkMode(receiptId: UUID): Unit = {
      setMode(ReceiptMode.Linking(receiptId))
      setExpenseFilter("")
      props.ctx.api.client.getExpenses.onComplete {
        case Success(resp) => setExpenseList(resp.data)
        case Failure(ex) => setError(ex.getMessage)
      }
    }

    def handleLinkToExpense(receiptId: UUID, expenseId: UUID): Unit =
      props.ctx.api.client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).onComplete {
        case Success(_) =>
          setError("")
          setMode(ReceiptMode.Viewing(Some(receiptId)))
          setLocalReceipts(cur => cur.map(r => if (r.id == receiptId) r.copy(expenseId = Some(expenseId)) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnlink(receiptId: UUID): Unit =
      props.ctx.api.client.unlinkReceipt(receiptId).onComplete {
        case Success(_) =>
          setError("")
          setLocalReceipts(cur => cur.map(r => if (r.id == receiptId) r.copy(expenseId = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleIgnoreReceipt(receiptId: UUID): Unit =
      props.ctx.api.client.ignoreReceipt(receiptId).onComplete {
        case Success(_) =>
          setError("")
          setLocalReceipts(cur => cur.map(r => if (r.id == receiptId) r.copy(ignoredAt = Some(Instant.now())) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnignoreReceipt(receiptId: UUID): Unit =
      props.ctx.api.client.unignoreReceipt(receiptId).onComplete {
        case Success(_) =>
          setError("")
          setLocalReceipts(cur => cur.map(r => if (r.id == receiptId) r.copy(ignoredAt = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    // Returns Right((merchantId, categoryId)) if gatable, Left(reason) otherwise.
    def gateCheck(r: GetReceipts.Response.Receipt): Either[String, (UUID, UUID)] =
      if (r.documentStatus != DocumentStatus.Parsed) Left(s"Not parsed")
      else if (r.expenseId.isDefined) Left("Already confirmed")
      else
        r.amount.toRight("Missing amount").flatMap { _ =>
          r.date.toRight("Missing date").flatMap { _ =>
            r.merchantName.toRight("No merchant name").flatMap { _ =>
              resolvedMerchantIds.get(r.id) match {
                case None => Left("Resolving…")
                case Some(None) => Left("Merchant not recognized")
                case Some(Some(mid)) =>
                  props.merchants.data.find(_.id == mid).flatMap(_.categoryId) match {
                    case None => Left("No default category")
                    case Some(catId) => Right((mid, catId))
                  }
              }
            }
          }
        }

    def enterBatchConfirmMode(): Unit = {
      setBatchConfirmMode(true)
      setBatchConfirmSelectedIds(Set.empty)
      // Batch-confirm state is left as is: earlier results stay visible on re-entry, as before.
      setResolvedMerchantIds(Map.empty[UUID, Option[UUID]])
      localReceipts
        .filter(r => r.documentStatus == DocumentStatus.Parsed && r.expenseId.isEmpty)
        .foreach { r =>
          val taxIdMatch = r.taxId.flatMap(tid => props.merchants.data.find(_.taxId.contains(tid)))
          taxIdMatch match {
            case Some(m) => setResolvedMerchantIds(cur => cur.updated(r.id, Some(m.id)))
            case None =>
              r.merchantName.foreach { name =>
                val resolvedId =
                  ImportRuleMatcher.findReceiptWinner(effectiveRuleList, name).flatMap(_.action.merchantIdOpt)
                setResolvedMerchantIds(cur => cur.updated(r.id, resolvedId))
              }
          }
        }
    }

    def handleBatchConfirm(ids: List[UUID]): Unit = {
      val total = ids.length
      setBatchConfirm(BatchState.Running(0, total, 0, Nil))
      def loop(remaining: List[UUID]): Unit =
        remaining match {
          case Nil =>
            setBatchConfirm(_.finished)
            setBatchConfirmSelectedIds(Set.empty)
          case id :: rest =>
            localReceipts.find(_.id == id) match {
              case None =>
                setBatchConfirm(_.recorded(id.toString.take(8), Left("Receipt not found")))
                loop(rest)
              case Some(r) =>
                val label = r.documentFilename
                gateCheck(r) match {
                  case Left(reason) =>
                    setBatchConfirm(_.recorded(label, Left(reason)))
                    loop(rest)
                  case Right((merchantId, catId)) =>
                    r.amount match {
                      case None =>
                        setBatchConfirm(_.recorded(label, Left("Missing amount")))
                        loop(rest)
                      case Some(amount) =>
                        val req = ConfirmReceipt.Request(
                          amount = Some(amount),
                          date = r.date,
                          time = None,
                          merchantId = Some(merchantId),
                          notes = None,
                          splits = List(CreateExpense.Split(catId, amount)),
                          parsedMerchantName = r.merchantName
                        )
                        props.ctx.api.client.confirmReceipt(id, req).onComplete {
                          case Success(resp) =>
                            setLocalReceipts(cur =>
                              cur.map(rx => if (rx.id == id) rx.copy(expenseId = resp.expenseId) else rx)
                            )
                            setBatchConfirm(_.recorded(label, Right(())))
                            loop(rest)
                          case Failure(ex) =>
                            setBatchConfirm(_.recorded(label, Left(ex.getMessage)))
                            loop(rest)
                        }
                    }
                }
            }
        }
      loop(ids)
    }

    def renderLinkPicker(receiptId: UUID): ReactElement = {
      val filtered = expenseList.filter { e =>
        val f = expenseFilter.toLowerCase
        f.isEmpty ||
        e.date.toString.contains(f) ||
        e.amount.toString.contains(f) ||
        e.merchantName.exists(_.toLowerCase.contains(f)) ||
        e.notes.exists(_.toLowerCase.contains(f))
      }
      Container(
        flexDirection = FlexDirection.column,
        child = Fragment(
          mui.Typography("Link to existing expense").sx(new CSSPropertiesUtils { fontWeight = "bold" }),
          mui.TextField
            .outlined()
            .label("Filter")
            .value(expenseFilter)
            .size("small")
            .onChange(e => setExpenseFilter(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          Fragment(
            filtered.take(20).map { e =>
              val label = s"${e.date} | ${e.amount} | ${e.merchantName.getOrElse("-")} | ${e.notes.getOrElse("")}"
              Container(
                flexDirection = FlexDirection.row,
                alignItems = Container.Alignment.center,
                child = Fragment(
                  mui.Typography(label).sx(secondaryStyling),
                  mui.Button
                    .normal()("Link")
                    .size("small")
                    .onClick(_ => handleLinkToExpense(receiptId, e.id))
                )
              ).withKey(e.id.toString)
            }*
          ),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .onClick(_ => setMode(ReceiptMode.Viewing(Some(receiptId))))
        )
      )
    }

    def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
      (mui.TextField.outlined.withProps(MuiCompat.textFieldProps(params, label, size = Some("small"))): ReactElement)

    def merchantAutocomplete(merchantInput: String): ReactElement = {
      val acProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
        options = props.merchants.data.toJSArray,
        renderInput = renderTextField("Merchant (optional)")
      )
      val acDyn = acProps.asInstanceOf[js.Dynamic]
      acDyn.freeSolo = true
      acDyn.size = "small"
      acDyn.inputValue = merchantInput
      acDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.merchantOptionLabel(opt)
      acDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
        (value: Any) match {
          case m: GetMerchants.Response.Merchant =>
            updateForm(_.copy(merchantId = m.id.toString, merchantInput = m.name))
          case _ => ()
        }
      acDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
        val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
        updateForm(f => f.copy(merchantInput = text, merchantId = if (rsn != "reset") "" else f.merchantId))
      }
      (mui.Autocomplete.withProps(acProps).withKey("merchant-ac"): ReactElement)
    }

    def splitRemainder(amountStr: String, splits: List[(String, String, String)]): BigDecimal = {
      val total = Try(BigDecimal(amountStr)).getOrElse(BigDecimal(0))
      val used = splits.flatMap { case (_, _, amt) => Try(BigDecimal(amt)).toOption }.sum
      total - used
    }

    def splitRow(
        catId: String,
        catInput: String,
        amt: String,
        i: Int,
        splits: List[(String, String, String)],
        setSplits: List[(String, String, String)] => Unit
    ): ReactElement = {
      val catAcProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
        options = props.categories.data.toJSArray,
        renderInput = renderTextField("Category")
      )
      val catDyn = catAcProps.asInstanceOf[js.Dynamic]
      catDyn.freeSolo = true
      catDyn.size = "small"
      catDyn.inputValue = catInput
      catDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.categoryOptionLabel(opt)
      catDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
        (value: Any) match {
          case c: GetCategories.Response.Category =>
            setSplits(splits.zipWithIndex.map { case ((ci, cin, a), j) =>
              if (j == i) (c.id.toString, c.name, a) else (ci, cin, a)
            })
          case _ => ()
        }
      catDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
        val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
        if (rsn != "reset")
          setSplits(splits.zipWithIndex.map { case ((ci, cin, a), j) =>
            if (j == i) ("", text, a) else (ci, cin, a)
          })
      }
      mui.ListItem
        .normal()(
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              (mui.Autocomplete.withProps(catAcProps).withKey(s"cat-ac-$i"): ReactElement),
              mui.TextField
                .outlined()
                .label("Amount")
                .value(amt)
                .onChange(e =>
                  setSplits(splits.zipWithIndex.map { case ((ci, cin, a), j) =>
                    if (j == i) (ci, cin, e.target.asInstanceOf[dom.HTMLInputElement].value) else (ci, cin, a)
                  })
                ),
              mui.Button
                .normal()("Remove")
                .onClick(_ => setSplits(splits.zipWithIndex.filter(_._2 != i).map(_._1)))
            )
          )
        )
        .withKey(i.toString)
        .build
    }

    def splitsSection(
        splits: List[(String, String, String)],
        setSplits: List[(String, String, String)] => Unit,
        amountStr: String
    ): ReactElement = {
      val rows =
        splits.zipWithIndex.map { case ((catId, catInput, amt), i) =>
          splitRow(catId, catInput, amt, i, splits, setSplits)
        }
      val rem = splitRemainder(amountStr, splits)
      Container(
        flexDirection = FlexDirection.column,
        child = Fragment(
          mui.List(rows),
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.Button
                .normal()("Add split")
                .onClick(_ => setSplits(splits :+ ("", "", ""))),
              mui.Typography(s"Uncategorized: $rem").sx(remainderStyling)
            )
          )
        )
      )
    }

    val batchActiveKey = if (batchConfirmMode || batch.isRunning) "inactive" else "idle"
    Hooks.useEffect(
      () => {
        if (batchConfirmMode || batch.isRunning) { () => () }
        else {
          val handleDragOver: js.Function1[dom.DragEvent, Unit] = e => e.preventDefault()
          val handleDrop: js.Function1[dom.DragEvent, Unit] = { e =>
            e.preventDefault()
            val fileList = e.dataTransfer.files
            val files = (0 until fileList.length).map(fileList(_)).toList
            handleFilesBatch(files)
          }
          dom.document.addEventListener("dragover", handleDragOver)
          dom.document.addEventListener("drop", handleDrop)
          () => {
            dom.document.removeEventListener("dragover", handleDragOver)
            dom.document.removeEventListener("drop", handleDrop)
            ()
          }
        }
      },
      js.Array(batchActiveKey)
    )

    val errorMsg = mui.Typography(error).color("error")

    def clearBatchResultsButton: ReactElement =
      mui.Button.normal()("Clear").size("small").onClick(_ => setBatch(_.cleared))

    // Single header section: upload area in normal mode, batch-confirm controls in batch mode.
    val header: slinky.core.facade.ReactElement = {
      val gatable = localReceipts.filter(r => gateCheck(r).isRight)
      val allSelected = gatable.nonEmpty && gatable.forall(r => batchConfirmSelectedIds.contains(r.id))
      if (batchConfirmMode)
        Container(
          flexDirection = FlexDirection.column,
          child = Fragment(
            Container(
              flexDirection = FlexDirection.row,
              alignItems = Container.Alignment.center,
              child = Fragment(
                mui.Button
                  .normal()("Back")
                  .size("small")
                  .onClick { _ =>
                    setBatchConfirmMode(false)
                    setBatchConfirmSelectedIds(Set.empty)
                    setResolvedMerchantIds(Map.empty[UUID, Option[UUID]])
                  },
                input(
                  `type` := "checkbox",
                  checked := allSelected,
                  onChange := { e =>
                    if (e.target.asInstanceOf[dom.HTMLInputElement].checked)
                      setBatchConfirmSelectedIds(gatable.map(_.id).toSet)
                    else
                      setBatchConfirmSelectedIds(Set.empty)
                  }
                ),
                mui.Typography(s"${batchConfirmSelectedIds.size} selected").sx(secondaryStyling),
                batchConfirm match {
                  case BatchState.Running(done, total, failed, _) =>
                    mui.Typography(
                      s"Confirming: $done/$total${if (failed > 0) s" ($failed failed)" else ""}…"
                    )
                  case _ =>
                    if (batchConfirmSelectedIds.nonEmpty)
                      mui.Button
                        .normal()(s"Confirm selected (${batchConfirmSelectedIds.size})")
                        .variant("contained")
                        .size("small")
                        .onClick(_ => handleBatchConfirm(batchConfirmSelectedIds.toList))
                    else Fragment()
                }
              )
            ),
            if (batchConfirm.results.nonEmpty)
              Container(
                flexDirection = FlexDirection.column,
                child = Fragment(
                  batchConfirm.results.map { case (label, outcome) =>
                    val (text, textColor) = outcome match {
                      case Right(_) => (s"✓ $label", "var(--lc-success-text)")
                      case Left(err) => (s"✗ $label: $err", "var(--lc-error)")
                    }
                    mui
                      .Typography(text)
                      .sx(new CSSPropertiesUtils { fontSize = "0.8em"; color = textColor })
                      .withKey(label)
                  }*
                )
              )
            else Fragment()
          )
        )
      else
        Container(
          flexDirection = FlexDirection.column,
          child = Fragment(
            Container(
              flexDirection = FlexDirection.row,
              alignItems = Container.Alignment.center,
              child = Fragment(
                mui.TextField
                  .outlined()
                  .select(true)
                  .label("Kind")
                  .value(uploadKind)
                  .onChange(e => setUploadKind(e.target.asInstanceOf[dom.HTMLInputElement].value))(
                    mui.MenuItem.normal()("Receipt").value("Receipt").withKey("Receipt").build,
                    mui.MenuItem.normal()("Invoice").value("Invoice").withKey("Invoice").build
                  ),
                batch match {
                  case BatchState.Running(done, total, failed, _) =>
                    mui.Typography(
                      s"Uploading: $done/$total${if (failed > 0) s" ($failed failed)" else ""}…"
                    )
                  case _ =>
                    Fragment(
                      input(
                        `type` := "file",
                        multiple := true,
                        onChange := { e => handleFileChange(e.target.asInstanceOf[dom.HTMLInputElement]) }
                      ),
                      mui.Typography("or drop files here").sx(secondaryStyling)
                    )
                },
                mui.Button
                  .normal()("Batch confirm")
                  .variant("outlined")
                  .size("small")
                  .onClick(_ => enterBatchConfirmMode())
              )
            ),
            if (batch.results.nonEmpty)
              Container(
                flexDirection = FlexDirection.column,
                child = Fragment(
                  Fragment(
                    batch.results.map { case (name, outcome) =>
                      val (label, textColor) = outcome match {
                        case Right(_) => (s"✓ $name", "var(--lc-success-text)")
                        case Left(err) => (s"✗ $name: $err", "var(--lc-error)")
                      }
                      mui
                        .Typography(label)
                        .sx(new CSSPropertiesUtils { fontSize = "0.8em"; color = textColor })
                        .withKey(name)
                    }*
                  ),
                  clearBatchResultsButton
                )
              )
            else Fragment()
          )
        )
    }

    val confirmable: List[(GetReceipts.Response.Receipt, (UUID, UUID))] =
      if (batchConfirmMode)
        localReceipts
          .filter(r => r.documentStatus == DocumentStatus.Parsed && r.expenseId.isEmpty)
          .sortBy(_.createdAt)(using Ordering[Instant].reverse)
          .flatMap(r => gateCheck(r).toOption.map(r -> _))
      else Nil

    def renderBatchItem(r: GetReceipts.Response.Receipt, gate: (UUID, UUID)): slinky.core.facade.ReactElement = {
      val (mid, catId) = gate
      mui.Box
        .sx(cardStyle)(
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              input(
                `type` := "checkbox",
                checked := batchConfirmSelectedIds.contains(r.id),
                onChange := { e =>
                  if (e.target.asInstanceOf[dom.HTMLInputElement].checked)
                    setBatchConfirmSelectedIds(cur => cur + r.id)
                  else
                    setBatchConfirmSelectedIds(cur => cur - r.id)
                }
              ),
              Container(
                flexDirection = FlexDirection.column,
                child = Fragment(
                  mui.Typography(r.documentFilename), {
                    val parts = List(
                      r.amount.map(a => s"Amount: $a"),
                      r.date.map(d => s"Date: $d"),
                      r.merchantName.map(n => s"Vendor: $n")
                    ).flatten
                    if (parts.nonEmpty) mui.Typography(parts.mkString("  |  ")).sx(parsedStyling)
                    else Fragment()
                  }, {
                    val mName = props.merchants.data.find(_.id == mid).map(_.name).getOrElse("")
                    val cName = props.categories.data.find(_.id == catId).map(_.name).getOrElse("")
                    mui.Typography(s"→ $mName ($cName)").sx(parsedStyling)
                  }
                )
              )
            )
          )
        )
        .withKey(r.id.toString)
    }

    def renderReceiptDetail(r: GetReceipts.Response.Receipt): ReactElement = {
      val isPending =
        r.documentStatus == DocumentStatus.Ingested || r.documentStatus == DocumentStatus.Processing || r.documentStatus == DocumentStatus.Analyzed
      val isPolling = pollingIds.contains(r.id)

      val (statusLabel, statusBg, statusFg) = {
        val confirmed = r.expenseId.isDefined
        val ignored = r.ignoredAt.isDefined
        r.documentStatus match {
          case DocumentStatus.Parsed if confirmed => ("Confirmed", "var(--lc-tint)", "var(--lc-brand-dark)")
          case DocumentStatus.Parsed if ignored => ("Ignored", "var(--lc-surface)", "var(--lc-text-2)")
          case DocumentStatus.Parsed => ("Needs review", "var(--lc-badge-review-bg)", "var(--lc-badge-review-text)")
          case DocumentStatus.ParseFailed | DocumentStatus.Unsupported =>
            ("Failed", "var(--lc-badge-error-bg)", "var(--lc-error)")
          case _ if isPending => ("Processing", "var(--lc-badge-info-bg)", "var(--lc-link)")
          case _ => ("—", "var(--lc-surface)", "var(--lc-text-2)")
        }
      }

      val actionArea: ReactElement = mode match {
        case ReceiptMode.Confirming(id, cf) if id == r.id =>
          val rowSuggestStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "#555" }
          val rowSelectedStyling =
            new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-link)"; fontWeight = "bold" }
          val receiptAmt = Try(BigDecimal(cf.amount)).toOption
          val receiptDate = if (cf.date.isEmpty) None else Some(cf.date)
          val receiptMerchant = Option(cf.merchantId)
            .filter(_.nonEmpty)
            .flatMap(id => Try(java.util.UUID.fromString(id)).toOption)
            .flatMap(id => props.merchants.data.find(_.id == id))
            .map(_.name.toLowerCase)
          val rankedRows = rowList
            .map { row =>
              val amtMatch = if (receiptAmt.exists(_ == row.amount.abs)) 4 else 0
              val dateMatch = if (receiptDate.exists(_ == row.date.toString)) 2 else 0
              val descMatch = receiptMerchant match {
                case Some(mn) if row.description.toLowerCase.contains(mn) => 1
                case _ => 0
              }
              (row, amtMatch + dateMatch + descMatch)
            }
            .filter { case (_, score) => score >= 4 || (score >= 2 && score < 4) }
            .sortBy { case (_, score) => -score }
          form(onSubmit := { ev =>
            ev.preventDefault()
            handleConfirm(r.id, cf)
          })(
            Container(
              flexDirection = FlexDirection.column,
              child = Fragment(
                Container(
                  flexDirection = FlexDirection.row,
                  alignItems = Container.Alignment.center,
                  child = Fragment(
                    mui.TextField
                      .outlined()
                      .autoFocus(true)
                      .label("Amount")
                      .value(cf.amount)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                        updateForm(_.copy(amount = v))
                      },
                    mui.TextField
                      .outlined()
                      .label("Date (YYYY-MM-DD)")
                      .value(cf.date)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                        updateForm(_.copy(date = v))
                      },
                    mui.TextField
                      .outlined()
                      .label("Time (HH:MM, optional)")
                      .value(cf.time)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                        updateForm(_.copy(time = v))
                      },
                    merchantAutocomplete(cf.merchantInput),
                    mui.TextField
                      .outlined()
                      .label("Notes (optional)")
                      .value(cf.notes)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                        updateForm(_.copy(notes = v))
                      },
                    mui.Button
                      .normal()("Save")
                      .variant("contained")
                      .onClick(_ => handleConfirm(r.id, cf)),
                    mui.Button
                      .normal()("Cancel")
                      .onClick(_ => setMode(ReceiptMode.Viewing(Some(r.id))))
                  )
                ),
                splitsSection(cf.splits, s => updateForm(_.copy(splits = s)), cf.amount),
                if (rankedRows.nonEmpty)
                  Container(
                    flexDirection = FlexDirection.column,
                    child = Fragment(
                      mui.Typography("Attach bank row (optional):").sx(rowSuggestStyling),
                      Fragment(
                        rankedRows.take(5).map { case (row, _) =>
                          val isSelected = cf.rowId.contains(row.id)
                          val label = s"${row.date} | ${row.amount} | ${row.description.take(40)}"
                          Container(
                            flexDirection = FlexDirection.row,
                            alignItems = Container.Alignment.center,
                            child = Fragment(
                              mui.Typography(label).sx(if (isSelected) rowSelectedStyling else rowSuggestStyling),
                              if (isSelected)
                                mui.Button
                                  .normal()("Deselect")
                                  .size("small")
                                  .onClick(_ => updateForm(_.copy(rowId = None)))
                              else
                                mui.Button
                                  .normal()("Attach")
                                  .size("small")
                                  .onClick(_ => updateForm(_.copy(rowId = Some(row.id))))
                            )
                          ).withKey(row.id.toString)
                        }*
                      )
                    )
                  )
                else Fragment()
              )
            )
          )
        case ReceiptMode.Linking(id) if id == r.id =>
          renderLinkPicker(r.id)
        case _ =>
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              (mui.Button
                .normal()("View Ticket")
                .size("small")
                .variant("outlined")
                .onClick(_ => handleViewTicket(r.id, r.documentFilename))
                .build: ReactElement),
              if (r.ignoredAt.isDefined)
                (mui.Button
                  .normal()("Unignore")
                  .size("small")
                  .variant("outlined")
                  .onClick(_ => handleUnignoreReceipt(r.id))
                  .build: ReactElement)
              else if (r.documentStatus == DocumentStatus.Parsed && r.expenseId.isEmpty)
                Fragment(
                  mui.Button
                    .normal()("Confirm")
                    .variant("contained")
                    .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                    .onClick { _ =>
                      setMode(
                        ReceiptMode.Confirming(
                          r.id,
                          ConfirmForm.fromReceipt(r, props.merchants.data, effectiveRuleList)
                        )
                      )
                      props.ctx.api.client.getAllBankStatementRows.onComplete {
                        case Success(resp) =>
                          setRowList(
                            resp.data.filter(row => row.expenseId.isEmpty && row.ignoredAt.isEmpty && row.amount < 0)
                          )
                        case Failure(_) => ()
                      }
                    },
                  (mui.Button
                    .normal()("Link existing")
                    .variant("outlined")
                    .size("small")
                    .onClick(_ => openLinkMode(r.id))
                    .build: ReactElement),
                  (mui.Button
                    .normal()("Ignore")
                    .size("small")
                    .variant("outlined")
                    .onClick(_ => handleIgnoreReceipt(r.id))
                    .build: ReactElement)
                )
              else if (r.expenseId.isDefined)
                (mui.Button
                  .normal()("Unlink")
                  .size("small")
                  .onClick(_ => handleUnlink(r.id))
                  .build: ReactElement)
              else Fragment(),
              (mui.Button.normal()("Delete").onClick(_ => handleDelete(r.id)).build: ReactElement)
            )
          )
      }

      mui.Box.sx(new CSSPropertiesUtils { maxWidth = "640px" })(
        // Header: merchant name + status chip
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; alignItems = "center"; gap = "12px"; marginBottom = "16px"; flexWrap = "wrap"
        })(
          mui
            .Typography(
              if (isPending) "Scanning receipt…"
              else r.merchantName.getOrElse(r.documentFilename)
            )
            .sx(new CSSPropertiesUtils {
              fontSize = "20px"; fontWeight = "700"; color = "var(--lc-text)"; flex = "1"; minWidth = "0"
            }),
          (mui.Chip
            .label(statusLabel)
            .size("small")
            .sx(new CSSPropertiesUtils {
              backgroundColor = statusBg; color = statusFg
              height = "24px"; fontSize = "12px"; fontWeight = "600"
            }): ReactElement),
          if (isPolling) (mui.CircularProgress().size(16.0): ReactElement) else Fragment()
        ),
        // Inline preview
        previewUrl match {
          case None =>
            val previewFilename = r.documentFilename.toLowerCase
            val isPreviewable = previewFilename.endsWith(".pdf") || previewFilename.endsWith(".jpg") ||
              previewFilename.endsWith(".jpeg") || previewFilename.endsWith(".png")
            if (!isPreviewable)
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  height = "60px"; marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"
                  display = "flex"; alignItems = "center"; justifyContent = "center"
                })(
                  mui
                    .Typography("Cannot preview this file type — use View Ticket to open it")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
                ): ReactElement)
            else if (previewLoading)
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  height = "120px"; marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"
                  background = "repeating-linear-gradient(45deg, var(--lc-paper) 0px 8px, var(--lc-warm-bg) 8px 9px)"
                  display = "flex"; alignItems = "center"; justifyContent = "center"
                })(
                  mui
                    .Typography("Loading preview…")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
                ): ReactElement)
            else
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  height = "60px"; marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"
                  display = "flex"; alignItems = "center"; justifyContent = "center"
                })(
                  mui
                    .Typography("Preview failed to load")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
                ): ReactElement)
          case Some(url) =>
            val filename = r.documentFilename.toLowerCase
            if (filename.endsWith(".pdf"))
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  height = "480px"; marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"; overflow = "hidden"
                })(
                  iframe(
                    src := url,
                    slinky.web.html.style := new CSSPropertiesUtils {
                      width = "100%"; height = "100%"; border = "none"
                    }
                  )()
                ): ReactElement)
            else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png"))
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"; overflow = "hidden"; textAlign = "center"
                })(
                  img(
                    src := url,
                    alt := r.merchantName.getOrElse(r.documentFilename),
                    slinky.web.html.style := new CSSPropertiesUtils { maxWidth = "100%"; display = "block" }
                  )()
                ): ReactElement)
            else
              (mui.Box
                .sx(new CSSPropertiesUtils {
                  height = "60px"; marginBottom = "16px"; borderRadius = "8px"
                  border = "1px solid var(--lc-border)"
                  display = "flex"; alignItems = "center"; justifyContent = "center"
                })(
                  mui
                    .Typography("Cannot preview this file type — use View Ticket to open it")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
                ): ReactElement)
        },
        // Fields grid
        mui.Box.sx(
          js.Dynamic
            .literal(
              display = "grid",
              gridTemplateColumns = js.Dynamic.literal(xs = "1fr", md = "1fr 1fr"),
              gap = "14px 28px",
              marginBottom = "20px"
            )
            .asInstanceOf[CSSPropertiesUtils]
        )(
          Fragment(
            List(
              ("Amount", r.amount.map(a => s"$a").getOrElse("—")),
              ("Date", r.date.map(_.toString).getOrElse("—")),
              ("Merchant", r.merchantName.getOrElse("—")),
              ("Tax ID", r.taxId.getOrElse("—"))
            ).map { case (lbl, fval) =>
              (mui
                .Box()(
                  mui
                    .Typography(lbl)
                    .sx(new CSSPropertiesUtils {
                      fontSize = "11px"; fontWeight = "700"; letterSpacing = "0.06em"
                      textTransform = "uppercase"; color = "var(--lc-text-2)"; display = "block"
                    }),
                  mui.Typography(fval).sx(new CSSPropertiesUtils { fontSize = "15px"; color = "var(--lc-text)" })
                )
                .withKey(lbl): ReactElement)
            }*
          )
        ),
        // Linked expense info
        r.expenseId.fold(Fragment(): ReactElement) { eid =>
          val info = allExpenses.find(_.id == eid)
          val lbl = info match {
            case Some(e) => s"Linked expense: ${e.date} | ${e.amount} | ${e.merchantName.getOrElse("-")}"
            case None => s"Linked expense: ${eid.toString.take(8)}…"
          }
          (mui
            .Typography(lbl)
            .sx(new CSSPropertiesUtils {
              fontSize = "13px"; color = "var(--lc-link)"; marginBottom = "12px"; display = "block"
            })
            .build: ReactElement)
        },
        if (isPending) (mui.LinearProgress(): ReactElement) else Fragment(),
        // Action area
        mui.Box.sx(new CSSPropertiesUtils { borderTop = "1px solid var(--lc-border)"; paddingTop = "16px" })(
          actionArea
        )
      )
    }

    val countNeedsConfirm =
      localReceipts.count(r => r.documentStatus == DocumentStatus.Parsed && r.expenseId.isEmpty && r.ignoredAt.isEmpty)
    val countProcessing = localReceipts.count(r =>
      r.documentStatus == DocumentStatus.Processing || r.documentStatus == DocumentStatus.Analyzed || r.documentStatus == DocumentStatus.Ingested
    )
    val countConfirmed = localReceipts.count(_.expenseId.isDefined)
    val countIgnored = localReceipts.count(_.ignoredAt.isDefined)

    val toastSnackbar: ReactElement = toast match {
      case None => Fragment()
      case Some(msg) =>
        mui.Snackbar
          .open(true)
          .autoHideDuration(3000.0)
          .onClose((_, _) => setToast(None))(
            mui.Alert.severity("success")(msg)
          )
    }

    if (batchConfirmMode)
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; display = "flex"; flexDirection = "column"; gap = "8px" })(
        header,
        errorMsg,
        if (confirmable.nonEmpty)
          (Fragment(
            mui
              .Typography(s"CONFIRMABLE (${confirmable.size})")
              .sx(new CSSPropertiesUtils {
                fontWeight = "700"; marginTop = "8px"; fontSize = "12px"; color = "var(--lc-text-2)"
              }),
            Fragment(confirmable.map { case (r, gate) => renderBatchItem(r, gate) }*)
          ): ReactElement)
        else Fragment(),
        toastSnackbar
      )
    else
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; flex = "1"; minHeight = "0" })(
        if (error.nonEmpty)
          (mui.Box.sx(new CSSPropertiesUtils { padding = "8px 16px" })(errorMsg): ReactElement)
        else Fragment(),
        // Split view
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flex = "1"; minHeight = "0" })(
          // ── LEFT PANEL ──
          mui.Box.sx(new CSSPropertiesUtils {
            width = "380px"; flex = "0 0 380px"; borderRight = "1px solid var(--lc-border)"
            display = "flex"; flexDirection = "column"; backgroundColor = "var(--lc-paper)"
          })(
            // Upload controls
            mui.Box.sx(new CSSPropertiesUtils { padding = "14px 16px 0 16px" })(
              mui.Box.sx(new CSSPropertiesUtils {
                display = "flex"; alignItems = "center"; gap = "8px"; marginBottom = "10px"
              })(
                mui
                  .Typography("Receipts")
                  .sx(new CSSPropertiesUtils {
                    fontSize = "15px"; fontWeight = "600"; color = "var(--lc-text)"; flex = "1"
                  }),
                mui.TextField
                  .outlined()
                  .select(true)
                  .label("Kind")
                  .value(uploadKind)
                  .size("small")
                  .onChange(e => setUploadKind(e.target.asInstanceOf[dom.HTMLInputElement].value))(
                    mui.MenuItem.normal()("Receipt").value("Receipt").withKey("Receipt").build,
                    mui.MenuItem.normal()("Invoice").value("Invoice").withKey("Invoice").build
                  )
              ),
              (batch match {
                case BatchState.Running(done, total, failed, _) =>
                  (mui
                    .Typography(s"Uploading: $done/$total${if (failed > 0) s" ($failed failed)" else ""}…")
                    .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
                    .build: ReactElement)
                case _ =>
                  (mui.Box.sx(new CSSPropertiesUtils {
                    display = "flex"; alignItems = "center"; gap = "8px"; marginBottom = "6px"
                  })(
                    input(
                      `type` := "file",
                      multiple := true,
                      onChange := { e => handleFileChange(e.target.asInstanceOf[dom.HTMLInputElement]) }
                    ),
                    (mui.Button
                      .normal()("Batch confirm")
                      .variant("outlined")
                      .size("small")
                      .onClick(_ => enterBatchConfirmMode())
                      .build: ReactElement)
                  ): ReactElement)
              }),
              if (batch.results.nonEmpty)
                (mui.Box.sx(new CSSPropertiesUtils { marginBottom = "6px" })(
                  Fragment(
                    batch.results.map { case (name, outcome) =>
                      val (lbl2, clr2) = outcome match {
                        case Right(_) => (s"✓ $name", "var(--lc-success-text)")
                        case Left(err) => (s"✗ $name: $err", "var(--lc-error)")
                      }
                      (mui
                        .Typography(lbl2)
                        .sx(new CSSPropertiesUtils { fontSize = "11px"; color = clr2 })
                        .withKey(name): ReactElement)
                    }*
                  ),
                  clearBatchResultsButton
                ): ReactElement)
              else Fragment()
            ),
            // Filter tabs
            mui.Tabs
              .value(receiptFilter)
              .onChange((_, v) => { setReceiptFilter(MuiCompat.tabValue(v)); setReceiptPage(0) })
              .variant("scrollable")
              .scrollButtons(false)
              .sx(new CSSPropertiesUtils { borderBottom = "1px solid var(--lc-border)"; minHeight = "40px" })(
                mui.Tab
                  .normal()
                  .label(s"Needs review ($countNeedsConfirm)")
                  .value(FilterNeedsConfirm)
                  .sx(new CSSPropertiesUtils { minHeight = "40px"; fontSize = "12px"; padding = "0 10px" })
                  .build,
                mui.Tab
                  .normal()
                  .label(s"Processing ($countProcessing)")
                  .value(FilterProcessing)
                  .sx(new CSSPropertiesUtils { minHeight = "40px"; fontSize = "12px"; padding = "0 10px" })
                  .build,
                mui.Tab
                  .normal()
                  .label(s"Confirmed ($countConfirmed)")
                  .value(FilterConfirmed)
                  .sx(new CSSPropertiesUtils { minHeight = "40px"; fontSize = "12px"; padding = "0 10px" })
                  .build,
                mui.Tab
                  .normal()
                  .label(s"Ignored ($countIgnored)")
                  .value(FilterIgnored)
                  .sx(new CSSPropertiesUtils { minHeight = "40px"; fontSize = "12px"; padding = "0 10px" })
                  .build,
                mui.Tab
                  .normal()
                  .label(s"All (${localReceipts.size})")
                  .value(FilterAll)
                  .sx(new CSSPropertiesUtils { minHeight = "40px"; fontSize = "12px"; padding = "0 10px" })
                  .build
              ),
            // Receipt list
            {
              val receiptPageSize = 20
              val totalReceiptPages = math.ceil(visibleForFilter.size.toDouble / receiptPageSize).toInt
              val currentReceiptPage = math.min(receiptPage, math.max(0, totalReceiptPages - 1))
              val pagedReceipts = visibleForFilter.slice(
                currentReceiptPage * receiptPageSize,
                (currentReceiptPage + 1) * receiptPageSize
              )
              val receiptPaginationWidget: ReactElement =
                if (totalReceiptPages <= 1) Fragment()
                else {
                  val pProps = MuiCompat.paginationProps(
                    count = totalReceiptPages,
                    page = currentReceiptPage,
                    onChange = setReceiptPage,
                    size = Some("small")
                  )
                  mui.Box.sx(new CSSPropertiesUtils {
                    display = "flex"; justifyContent = "center"; padding = "8px 0";
                    borderTop = "1px solid var(--lc-border)"
                  })(
                    mui.Pagination.withProps(pProps)
                  )
                }
              Fragment(
                mui.Box.sx(new CSSPropertiesUtils { flex = "1"; overflowY = "auto" })(
                  if (visibleForFilter.isEmpty)
                    mui
                      .Typography("Nothing here.")
                      .sx(new CSSPropertiesUtils {
                        padding = "24px"; color = "var(--lc-text-2)"; fontSize = "14px"; display = "block"
                      })
                  else
                    Fragment(
                      pagedReceipts.map { r =>
                        val isSelected = selectedReceiptId.contains(r.id)
                        val isPending2 =
                          r.documentStatus == DocumentStatus.Processing || r.documentStatus == DocumentStatus.Analyzed || r.documentStatus == DocumentStatus.Ingested
                        val heading =
                          if (isPending2) "Scanning receipt…"
                          else r.merchantName.getOrElse(r.documentFilename)
                        val dateStr = r.date.map(_.toString).getOrElse("")
                        val amtStr = r.amount.map(a => s"$a").getOrElse("—")
                        (mui.Box
                          .sx(new CSSPropertiesUtils {
                            padding = "12px 16px"; cursor = "pointer"
                            borderBottom = "1px solid var(--lc-border)"
                            borderLeft = if (isSelected) "3px solid var(--lc-brand)" else "3px solid transparent"
                            paddingLeft = if (isSelected) "13px" else "16px"
                            backgroundColor = if (isSelected) "var(--lc-tint)" else "transparent"
                            display = "flex"; gap = "10px"; alignItems = "center"
                          })
                          .onClick(_ => {
                            setMode(ReceiptMode.Viewing(Some(r.id)))
                          })(
                            mui.Box.sx(new CSSPropertiesUtils {
                              width = "38px"; height = "38px"; flex = "0 0 38px"
                              borderRadius = "4px"; border = "1px solid var(--lc-border)"
                              background =
                                "repeating-linear-gradient(45deg, var(--lc-paper) 0px 4px, var(--lc-warm-bg) 4px 5px)"
                            })(),
                            mui.Box.sx(new CSSPropertiesUtils { flex = "1"; minWidth = "0" })(
                              mui
                                .Typography(heading)
                                .sx(new CSSPropertiesUtils {
                                  fontWeight = "600"; fontSize = "14px"; color = "var(--lc-text)"
                                  overflow = "hidden"; textOverflow = "ellipsis"; whiteSpace = "nowrap";
                                  display = "block"
                                }),
                              mui.Box.sx(new CSSPropertiesUtils {
                                display = "flex"; gap = "6px"; alignItems = "center"; marginTop = "2px"
                              })(
                                mui
                                  .Typography(dateStr)
                                  .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }),
                                if (r.ignoredAt.isDefined)
                                  (mui.Chip
                                    .label("Ignored")
                                    .size("small")
                                    .sx(new CSSPropertiesUtils {
                                      height = "16px"; fontSize = "10px"
                                      backgroundColor = "var(--lc-surface)"; color = "var(--lc-text-2)"
                                    }): ReactElement)
                                else if (r.expenseId.isDefined)
                                  (mui.Chip
                                    .label("Confirmed")
                                    .size("small")
                                    .sx(new CSSPropertiesUtils {
                                      height = "16px"; fontSize = "10px"
                                      backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
                                    }): ReactElement)
                                else Fragment()
                              )
                            ),
                            mui
                              .Typography(amtStr)
                              .sx(new CSSPropertiesUtils {
                                fontWeight = "700"; fontSize = "13px"; color = "var(--lc-text)"
                                fontFamily = "\"IBM Plex Mono\", monospace"; whiteSpace = "nowrap"
                              })
                          )
                          .withKey(r.id.toString): ReactElement)
                      }*
                    )
                ),
                receiptPaginationWidget
              )
            }
          ),
          // ── RIGHT PANEL ──
          mui.Box.sx(new CSSPropertiesUtils {
            flex = "1"; minWidth = "0"; padding = "24px"; overflowY = "auto"
          })(
            selectedReceiptId.flatMap(id => localReceipts.find(_.id == id)) match {
              case None =>
                mui.Box.sx(new CSSPropertiesUtils {
                  display = "flex"; alignItems = "center"; justifyContent = "center"; height = "100%"
                })(
                  mui
                    .Typography("Select a receipt to review")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "15px" })
                )
              case Some(r) => renderReceiptDetail(r)
            }
          )
        ),
        toastSnackbar
      )
  }
}
