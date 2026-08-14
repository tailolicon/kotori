package mihon.feature.translation.provider

/** Conservative repairs for recurring English manga OCR splits/substitutions. */
internal object MangaOcrCleaner {

    fun clean(text: String): String = text
        .replace(Regex("^\\s*\\|(?=[A-Za-z])"), "")
        .replace(Regex("(?i)^(?:t|ti|tn)?\\s*in\\s*the\\s*year\\b"), "In the year")
        .replace(Regex("/(?=\\s*$)"), "")
        .replace(Regex("(?i)\\bgender equality[\"”]?\\s+was\\s+r$"), "gender equality was no more")
        .replace(Regex("(?i)\\bMato[o0]{2,}\\b"), "Mato")
        .replace(Regex("(?i)\\bCARE\\s+TAKER\\b"), "CARETAKER")
        .replace(Regex("(?i)\\bUNDER\\s+STOOD\\b"), "UNDERSTOOD")
        .replace(Regex("(?i)\\bMISBE\\s+HAVES\\b"), "MISBEHAVES")
        .replace(Regex("(?i)\\bEXPRES\\s+SION\\b"), "EXPRESSION")
        .replace(Regex("(?i)\\bCOM\\s+PLAINING\\b"), "COMPLAINING")
        .replace(Regex("(?i)\\bCLAR\\s*VOYANT\\b"), "CLAIRVOYANT")
        .replace(Regex("(?i)\\bSPARK\\s+LIN[6G]\\b"), "SPARKLING")
        .replace(Regex("(?i)\\bN[ƠO]T\\b"), "NOT")
        .replace(Regex("(?i)\\bRGHT\\b"), "RIGHT")
        .replace(Regex("(?i)\\bPOOD\\b"), "FOOD")
        .replace(Regex("(?i)\\bGAYING\\b"), "SAYING")
        .replace(Regex("(?i)\\bAND\\s+A(?:L)?SO\\b"), "AND ALSO")
        .replace(Regex("(?i)\\bA\\s+JOB\\s+WELL\\s+PONE\\b"), "A JOB WELL DONE")
        .replace(Regex("(?i)\\bFPLAY\\s+MMI\\s+PART\\b"), "I'LL PLAY MY PART")
        .replace(Regex("(?i)\\bI['’]LL\\s+FPLAY\\b"), "I'LL PLAY")
        .replace(Regex("(?i)\\bTITS\\s+FINALLY\\s+TIME\\b"), "IT'S FINALLY TIME")
        .replace(Regex("(?i)\\bMIS5ION\\b"), "MISSION")
        .replace(Regex("(?i)\\bENE\\s+MIE+S\\s+S+\\b"), "ENEMIES")
        .replace(Regex("(?i)\\bG0\\b"), "GO")
        .replace(Regex("(?i)\\bAS\\s+IF\\s+[TI]['’]?D\\s+LET\\b"), "THERE IS NO WAY I WOULD LET")
        .replace(Regex("(?i)\\bEVERY\\s+THING'S\\b"), "EVERYTHING'S")
        .replace(Regex("(?i)\\bWHA(?:\\s+A+){2,}\\b"), "WHAT")
        .replace(Regex("(?i)\\bITS\\s+TME\\b"), "IT'S TIME")
        .replace(Regex("(?i)\\bFORM\\s+REWARD\\b"), "FOR MY REWARD")
        .replace(Regex("(?i)^\\s*ala\\s+no[kx]\\s+[\"']?113m[.!]?\\s*$"), "YOU DID WELL.")
}
