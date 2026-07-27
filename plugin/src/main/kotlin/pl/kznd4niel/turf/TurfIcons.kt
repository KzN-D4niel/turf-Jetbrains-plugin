package pl.kznd4niel.turf

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object TurfIcons {
    /** Podmienia ikone typu pliku w drzewie dla plikow nalezacych do AI. */
    @JvmField
    val AI: Icon = IconLoader.getIcon("/icons/ai.svg", TurfIcons::class.java)
}
