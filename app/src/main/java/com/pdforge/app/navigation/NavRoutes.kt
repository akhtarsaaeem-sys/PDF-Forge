package com.pdforge.app.navigation

sealed class NavRoutes(val route: String) {
    data object Splash : NavRoutes("splash")
    data object Home : NavRoutes("home")
    data object Files : NavRoutes("files")
    data object Settings : NavRoutes("settings")
    data object Category : NavRoutes("category/{categoryId}") {
        fun createRoute(categoryId: String) = "category/$categoryId"
    }
    data object Tool : NavRoutes("tool/{toolId}") {
        fun createRoute(toolId: String) = "tool/$toolId"
    }
    data object Result : NavRoutes("result/{toolId}/{success}") {
        fun createRoute(toolId: String, success: Boolean) = "result/$toolId/$success"
    }
    data object Viewer : NavRoutes("viewer/{filePath}") {
        /**
         * Encodes [input] with URL-safe Base64 (no '+', '/', '%', '?' or '#' characters).
         *
         * Navigation arguments are URI-decoded multiple times while a route string is
         * parsed (Uri.getPathSegments + NavType.StringType), so percent-encoding a raw
         * path/URI here corrupts references containing '+' or '%xx' sequences. Base64's
         * URL-safe alphabet survives every decode layer unchanged, so the original
         * content:// URI, file:// URI or filesystem path round-trips losslessly.
         */
        fun createRoute(input: String) = "viewer/" +
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.toByteArray(Charsets.UTF_8))
    }
    data object Scanner : NavRoutes("scanner")
    data object Organize : NavRoutes("organize")
    data object EditPdf : NavRoutes("edit_pdf?uri={uri}&path={path}") {
        fun createRoute(uri: String? = null, path: String? = null): String {
            return buildString {
                append("edit_pdf?")
                if (uri != null) append("uri=$uri")
                if (uri != null && path != null) append("&")
                if (path != null) append("path=$path")
            }
        }
    }
    data object PdfForms : NavRoutes("pdf_forms")
    data object RedactPdf : NavRoutes("redact_pdf?uri={uri}&path={path}") {
        fun createRoute(uri: String? = null, path: String? = null): String {
            return buildString {
                append("redact_pdf?")
                if (uri != null) append("uri=$uri")
                if (uri != null && path != null) append("&")
                if (path != null) append("path=$path")
            }
        }
    }
    data object ComparePdf : NavRoutes("compare_pdf")
    data object SignPdf : NavRoutes("sign_pdf?uri={uri}&path={path}") {
        fun createRoute(uri: String? = null, path: String? = null): String {
            return buildString {
                append("sign_pdf?")
                if (uri != null) append("uri=$uri")
                if (uri != null && path != null) append("&")
                if (path != null) append("path=$path")
            }
        }
    }
}
