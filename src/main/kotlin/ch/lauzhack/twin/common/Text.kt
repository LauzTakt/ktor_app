package ch.lauzhack.twin.common

import java.text.Normalizer

private val DIACRITICS = Regex("\\p{M}+")

/**
 * Station-name search key: trimmed, lower case, accents removed ("Zürich" and "zurich" match; ß -> ss). The one
 * normalisation behind every station search (REST `?q=`, the assistant's resolver, the passenger app's place matching).
 */
fun searchKey(s: String): String =
    Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace("ß", "ss")
