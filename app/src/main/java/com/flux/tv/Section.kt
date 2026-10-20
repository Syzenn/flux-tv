package com.flux.tv

/** Une entrée de la colonne de gauche. */
sealed class Section(val label: String, val isAction: Boolean = false) {
    object Favorites : Section("★  Favoris")
    object All : Section("Toutes les chaînes")
    class Cat(val category: Category) : Section(category.name)
    object Refresh : Section("⟳  Actualiser la liste", isAction = true)
    object Logout : Section("Se déconnecter", isAction = true)

    fun sameAs(other: Section?): Boolean {
        if (other == null) return false
        if (this is Cat && other is Cat) return category.id == other.category.id
        return this === other
    }
}
