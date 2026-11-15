package net.wiringbits.forms

import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleActionKind}
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExistingSelections,
  ExpRuleAmount,
  ExpRuleDescription,
  ExpRulePattern
}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

/** The import-rule form, modelled as one [[FormData]] per action kind.
  *
  * Each kind carries only the fields it uses, so there is no action tag beside merchant/category fields that are valid
  * for only some kinds. A required select/create field holds an [[ExistingOrNewValue]] (blank is invalid); the optional
  * category override holds an `Option`. Every field is a validated `FormField`, so validation runs live in the fields.
  * `submitRequest` yields a [[Resolved]] variant once the fields are valid; resolving or creating the selected merchant
  * or category stays a side effect of the component. The action dropdown is a selector of which variant is active (see
  * [[BankRowRuleFormData.changeKind]]), not a field.
  */
sealed trait BankRowRuleFormData extends FormData[BankRowRuleFormData.Resolved] {
  def actionKind: ImportRuleActionKind
  def pattern: FormField[ExpRulePattern]
  def amount: FormField[Option[ExpRuleAmount]]

  def withPattern(field: FormField[ExpRulePattern]): BankRowRuleFormData
  def withAmount(field: FormField[Option[ExpRuleAmount]]): BankRowRuleFormData
}

object BankRowRuleFormData {

  /** The validated inputs a submit yields, one variant per action kind. Merchant/category are still
    * [[ExistingOrNewValue]]s (name + optional id) because resolving them to ids is the component's async side effect.
    */
  enum Resolved {
    case SetMerchant(
        pattern: String,
        amount: Option[BigDecimal],
        description: Option[String],
        merchant: ExistingOrNewValue[MerchantName, UUID],
        category: Option[ExistingOrNewValue[CategoryName, UUID]]
    )
    case SetCategory(
        pattern: String,
        amount: Option[BigDecimal],
        description: Option[String],
        category: ExistingOrNewValue[CategoryName, UUID]
    )
    case Ignore(pattern: String, amount: Option[BigDecimal])
  }

  case class SetMerchant(
      pattern: FormField[ExpRulePattern],
      amount: FormField[Option[ExpRuleAmount]],
      description: FormField[Option[ExpRuleDescription]],
      merchant: FormField[ExistingOrNewValue[MerchantName, UUID]],
      category: FormField[Option[ExistingOrNewValue[CategoryName, UUID]]]
  ) extends BankRowRuleFormData {
    override def actionKind: ImportRuleActionKind = ImportRuleActionKind.SetMerchant
    override def fields: List[FormField[?]] = List(pattern, amount, description, merchant, category)

    override def submitRequest: Option[Resolved] =
      for {
        p <- pattern.valueOpt
        a <- amount.valueOpt
        d <- description.valueOpt
        m <- merchant.valueOpt
        c <- category.valueOpt
      } yield Resolved.SetMerchant(
        pattern = p.string,
        amount = a.map(_.value),
        description = d.map(_.string),
        merchant = m,
        category = c
      )

    override def withPattern(field: FormField[ExpRulePattern]): BankRowRuleFormData = copy(pattern = field)
    override def withAmount(field: FormField[Option[ExpRuleAmount]]): BankRowRuleFormData = copy(amount = field)
  }

  case class SetCategory(
      pattern: FormField[ExpRulePattern],
      amount: FormField[Option[ExpRuleAmount]],
      description: FormField[Option[ExpRuleDescription]],
      category: FormField[ExistingOrNewValue[CategoryName, UUID]]
  ) extends BankRowRuleFormData {
    override def actionKind: ImportRuleActionKind = ImportRuleActionKind.SetCategory
    override def fields: List[FormField[?]] = List(pattern, amount, description, category)

    override def submitRequest: Option[Resolved] =
      for {
        p <- pattern.valueOpt
        a <- amount.valueOpt
        d <- description.valueOpt
        c <- category.valueOpt
      } yield Resolved.SetCategory(
        pattern = p.string,
        amount = a.map(_.value),
        description = d.map(_.string),
        category = c
      )

    override def withPattern(field: FormField[ExpRulePattern]): BankRowRuleFormData = copy(pattern = field)
    override def withAmount(field: FormField[Option[ExpRuleAmount]]): BankRowRuleFormData = copy(amount = field)
  }

  case class Ignore(
      pattern: FormField[ExpRulePattern],
      amount: FormField[Option[ExpRuleAmount]]
  ) extends BankRowRuleFormData {
    override def actionKind: ImportRuleActionKind = ImportRuleActionKind.Ignore
    override def fields: List[FormField[?]] = List(pattern, amount)

    override def submitRequest: Option[Resolved] =
      for {
        p <- pattern.valueOpt
        a <- amount.valueOpt
      } yield Resolved.Ignore(pattern = p.string, amount = a.map(_.value))

    override def withPattern(field: FormField[ExpRulePattern]): BankRowRuleFormData = copy(pattern = field)
    override def withAmount(field: FormField[Option[ExpRuleAmount]]): BankRowRuleFormData = copy(amount = field)
  }

  private def patternField(value: Option[ValidationResult[ExpRulePattern]]): FormField[ExpRulePattern] =
    new FormField(label = "Pattern", name = "pattern", required = true, value = value)

  private def amountField(seed: String): FormField[Option[ExpRuleAmount]] =
    new FormField(
      label = "Amount",
      name = "amount",
      required = false,
      value = Some(ExpRuleAmount.optionalValidator(seed))
    )

  private def descriptionField(seed: String): FormField[Option[ExpRuleDescription]] =
    new FormField(
      label = "Description",
      name = "description",
      required = false,
      value = Some(ExpRuleDescription.optionalValidator(seed))
    )

  private def requiredMerchant(
      value: Option[ValidationResult[ExistingOrNewValue[MerchantName, UUID]]]
  ): FormField[ExistingOrNewValue[MerchantName, UUID]] =
    new FormField(label = "Merchant", name = "merchant", required = true, value = value)

  private def requiredCategory(
      value: Option[ValidationResult[ExistingOrNewValue[CategoryName, UUID]]]
  ): FormField[ExistingOrNewValue[CategoryName, UUID]] =
    new FormField(label = "Category", name = "category", required = true, value = value)

  private def optionalCategory(
      value: ValidationResult[Option[ExistingOrNewValue[CategoryName, UUID]]]
  ): FormField[Option[ExistingOrNewValue[CategoryName, UUID]]] =
    new FormField(label = "Category (optional)", name = "category", required = false, value = Some(value))

  private def validSelection[T](input: String, selection: Option[T]): Option[ValidationResult[T]] =
    selection.map(value => ValidationResult.Valid(input, value))

  private def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  def initial(patternSeed: String, amountSeed: String, descriptionSeed: String): BankRowRuleFormData =
    SetMerchant(
      pattern = patternField(Some(ExpRulePattern.validate(patternSeed))),
      amount = amountField(amountSeed),
      description = descriptionField(descriptionSeed),
      merchant = requiredMerchant(None),
      category = optionalCategory(ValidationResult.Valid("", None))
    )

  /** A blank create form: the pattern is untouched, so no error shows yet but the form is not submittable. */
  def empty: BankRowRuleFormData =
    SetMerchant(
      pattern = patternField(None),
      amount = amountField(""),
      description = descriptionField(""),
      merchant = requiredMerchant(None),
      category = optionalCategory(ValidationResult.Valid("", None))
    )

  /** The edit form of a stored rule. Every value is validated rather than trusted, so a stored value the form would
    * reject shows as invalid. A stored negative amount seeds as its positive magnitude. `categoryName` is the
    * set_category target or, for set_merchant, the override category.
    */
  def fromRule(r: GetImportRules.Rule): BankRowRuleFormData = {
    val pattern = patternField(Some(ExpRulePattern.validate(r.pattern)))
    val amount = amountField(r.amount.fold("")(_.abs.bigDecimal.toPlainString))
    val description = descriptionField(r.action.descriptionOpt.getOrElse(""))
    def categoryWithId(id: Option[UUID]) =
      for {
        name <- r.categoryName
        categoryId <- id
      } yield ExistingSelections.existingCategory(name, categoryId)

    r.action.kind match {
      case ImportRuleActionKind.SetMerchant =>
        val merchant = for {
          name <- r.merchantName
          merchantId <- r.action.merchantIdOpt
        } yield ExistingSelections.existingMerchant(name, merchantId)
        SetMerchant(
          pattern = pattern,
          amount = amount,
          description = description,
          merchant = requiredMerchant(merchant),
          category = optionalCategory(
            categoryWithId(r.action.overrideCategoryIdOpt).fold(ValidationResult.Valid("", None))(asOptional)
          )
        )
      case ImportRuleActionKind.SetCategory =>
        SetCategory(
          pattern = pattern,
          amount = amount,
          description = description,
          category = requiredCategory(categoryWithId(r.action.categoryIdOpt))
        )
      case ImportRuleActionKind.Ignore =>
        Ignore(pattern, amount)
    }
  }

  /** Switches the active variant, carrying over the fields the kinds share: pattern and amount (all kinds), and the
    * description and category selection (shared by SetMerchant and SetCategory). The category just moves between its
    * required and optional shapes, unwrapping its selected value when there is one. Only the SetMerchant-only merchant
    * field is reset.
    */
  def changeKind(current: BankRowRuleFormData, kind: ImportRuleActionKind): BankRowRuleFormData = {
    val description = current match {
      case fd: SetMerchant => fd.description
      case fd: SetCategory => fd.description
      case _: Ignore => descriptionField("")
    }
    val (categoryInput, categorySelection) = current match {
      case fd: SetMerchant => (fd.category.inputValue, fd.category.valueOpt.flatten)
      case fd: SetCategory => (fd.category.inputValue, fd.category.valueOpt)
      case _: Ignore => ("", None)
    }
    kind match {
      case ImportRuleActionKind.SetMerchant =>
        SetMerchant(
          current.pattern,
          current.amount,
          description,
          requiredMerchant(None),
          optionalCategory(ValidationResult.Valid(categoryInput, categorySelection))
        )
      case ImportRuleActionKind.SetCategory =>
        SetCategory(
          current.pattern,
          current.amount,
          description,
          requiredCategory(validSelection(categoryInput, categorySelection))
        )
      case ImportRuleActionKind.Ignore =>
        Ignore(current.pattern, current.amount)
    }
  }
}
