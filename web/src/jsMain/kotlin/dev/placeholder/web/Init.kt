package dev.placeholder.web

import com.varabyte.kobweb.core.init.InitKobweb
import com.varabyte.kobweb.core.init.InitKobwebContext
import dev.placeholder.web.pages.MissingScreen

@InitKobweb
fun initKobweb(ctx: InitKobwebContext) {
    // Any URL that isn't a page or a slug gets the same quiet "nothing here" screen.
    ctx.router.setErrorPage { pageContext -> MissingScreen(pageContext.route.path) }
}
