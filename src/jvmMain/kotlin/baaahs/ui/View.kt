package baaahs.ui

actual interface View

actual interface Icon

actual interface DialogHolder {
    actual fun showDialog(view: View)
    actual fun showMenuDialog(title: String, options: List<DialogMenuItem>)
    actual fun closeDialog()
}

actual fun confirm(message: String): Boolean = TODO("confirm not implemented")