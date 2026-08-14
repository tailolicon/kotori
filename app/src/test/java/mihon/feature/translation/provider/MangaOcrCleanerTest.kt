package mihon.feature.translation.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaOcrCleanerTest {

    @Test
    fun `repairs split English words without rewriting unrelated prose`() {
        assertEquals("UNDERSTOOD?", MangaOcrCleaner.clean("UNDER STOOD?"))
        assertEquals("NEI IS A CLAIRVOYANT.", MangaOcrCleaner.clean("NEI IS A CLARVOYANT."))
        assertEquals("THAT EXPRESSION...", MangaOcrCleaner.clean("THAT EXPRES SION..."))
        assertEquals("THE CARETAKER", MangaOcrCleaner.clean("THE CARE TAKER"))
        assertEquals("MATO IS HERE", MangaOcrCleaner.clean("MATO IS HERE"))
    }

    @Test
    fun `repairs the evidenced manga OCR failures before translation`() {
        assertEquals("I'LL PLAY MY PART AND BECOME A HERO", MangaOcrCleaner.clean("FPLAY MMI PART AND BECOME A HERO"))
        assertEquals("I'LL PLAY MY PART", MangaOcrCleaner.clean("I'LL FPLAY MY PART"))
        assertEquals("IT'S FINALLY TIME.", MangaOcrCleaner.clean("TITS FINALLY TIME."))
        assertEquals("OUR MISSION", MangaOcrCleaner.clean("OUR MIS5ION"))
        assertEquals("WHERE ARE THE ENEMIES!!", MangaOcrCleaner.clean("WHERE ARE THE ENE MIEEES SSS!!"))
        assertEquals("LET'S GO", MangaOcrCleaner.clean("LET'S G0"))
        assertEquals("THERE IS NO WAY I WOULD LET YOU GO", MangaOcrCleaner.clean("AS IF T'D LET YOU GO"))
        assertEquals("ALRIGHT! EVERYTHING'S FINE", MangaOcrCleaner.clean("|ALRIGHT! EVERY THING'S FINE"))
        assertEquals("WHAT!?", MangaOcrCleaner.clean("WHA AAA AAA AA!?"))
        assertEquals("IT'S TIME", MangaOcrCleaner.clean("ITS TME"))
        assertEquals("FOR MY REWARD.", MangaOcrCleaner.clean("FORM REWARD."))
        assertEquals("YOU DID WELL.", MangaOcrCleaner.clean("ala nok \"113M"))
        assertEquals("A JOB WELL DONE FEELS GREAT!", MangaOcrCleaner.clean("A JOB WELL PONE FEELS GREAT!"))
        assertEquals("AND ALSO", MangaOcrCleaner.clean("AND ASO"))
        assertEquals("AND ALSO", MangaOcrCleaner.clean("AND AlsO"))
    }
}
