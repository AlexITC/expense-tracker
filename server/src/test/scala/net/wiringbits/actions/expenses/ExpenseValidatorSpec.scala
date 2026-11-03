package net.wiringbits.actions.expenses

import net.wiringbits.common.ExpenseSplits
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

class ExpenseValidatorSpec extends RepositorySpec with RepositoryUtils {

  private def validator(using repositories: RepositoryComponents) =
    new ExpenseValidator(repositories.merchants, repositories.categories)

  "validate" should {
    "fail when there are no splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val error = intercept[RuntimeException](validator.validate(user.id, BigDecimal(100), None, None, Nil).futureValue)
      error.getCause.getMessage must be(ExpenseSplits.NotFullyAllocatedError)
    }

    "fail when the splits add up to less than the amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val splits = List(category.id -> BigDecimal(60))
      val error =
        intercept[RuntimeException](validator.validate(user.id, BigDecimal(100), None, None, splits).futureValue)
      error.getCause.getMessage must be(ExpenseSplits.NotFullyAllocatedError)
    }

    "fail when the splits add up to more than the amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val splits = List(category.id -> BigDecimal(150))
      val error =
        intercept[RuntimeException](validator.validate(user.id, BigDecimal(100), None, None, splits).futureValue)
      error.getCause.getMessage must be(ExpenseSplits.NotFullyAllocatedError)
    }

    "succeed when the splits add up to exactly the amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val food = createCategory(user.id, "Food").futureValue
      val home = createCategory(user.id, "Home").futureValue
      val splits = List(food.id -> BigDecimal("60.50"), home.id -> BigDecimal("39.50"))
      validator.validate(user.id, BigDecimal(100), None, None, splits).futureValue
    }
  }
}
