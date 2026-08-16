package app.termora

fun main() {
    StartupProbe.mark(StartupProbe.MAIN_ENTER)
    try {
        ApplicationInitializr().run()
    } finally {
        StartupProbe.mark(StartupProbe.MAIN_RETURN)
    }
}
