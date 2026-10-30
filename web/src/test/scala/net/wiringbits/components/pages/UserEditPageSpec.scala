package net.wiringbits.components.pages

import net.wiringbits.components.pages.UserEditPage.TokenAction
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class UserEditPageSpec extends AnyWordSpec {

  "TokenAction" should {
    "expose nothing while Idle" in {
      val action: TokenAction[String] = TokenAction.Idle
      action.isWorking mustBe false
      action.tokenOpt mustBe None
      action.errorOpt mustBe None
    }

    "be working without token or error while Working" in {
      val action: TokenAction[String] = TokenAction.Working
      action.isWorking mustBe true
      action.tokenOpt mustBe None
      action.errorOpt mustBe None
    }

    "expose only the token when Loaded" in {
      val action: TokenAction[String] = TokenAction.Loaded("abc")
      action.isWorking mustBe false
      action.tokenOpt mustBe Some("abc")
      action.errorOpt mustBe None
    }

    "expose only the error when Failed" in {
      val action: TokenAction[String] = TokenAction.Failed("boom")
      action.isWorking mustBe false
      action.tokenOpt mustBe None
      action.errorOpt mustBe Some("boom")
    }
  }
}
