// CaregiverNoticeActivity.kt

package com.t4paN.AVA

import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * The (!) — everything that silently breaks AVA if nobody says it out loud.
 *
 * Written for 30 seconds of attention, read once, standing in someone's kitchen,
 * probably while being talked at. Casual, TL;DR, no jargon. It is the highest-value
 * text in the app: a caregiver who gives up halfway leaves a half-configured phone,
 * and the primary user cannot tell us what is wrong.
 *
 * Body copy is a DRAFT by Claude for t4paN to rewrite — road2release.md §7 lists the
 * Greek wording as t4paN's call, because it sets the tone of the whole caregiver
 * relationship. The *structure* is what is load-bearing: battery first, because it is
 * the most likely reason a correctly-installed AVA "just stops".
 */
class CaregiverNoticeActivity : CaregiverScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caregiver_notice)
        padForSystemBars(findViewById(R.id.noticeContent))
        title = "Οδηγίες για τον βοηθό"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.txtNoticeBody).text = NOTICE
        findViewById<Button>(R.id.btnNoticeDone).setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        val NOTICE = """
            Η AVA είναι ένα κουμπί. Το πατάς, λες ένα όνομα, γίνεται η κλήση. Αυτό είναι όλο.

            Πριν φύγεις, έξι πράγματα. Αν λείψει κάποιο, η AVA θα σταματήσει να δουλεύει και δεν θα υπάρχει κανείς να σου το πει.


            1) ΚΛΕΙΣΕ ΤΗΝ ΕΞΟΙΚΟΝΟΜΗΣΗ ΜΠΑΤΑΡΙΑΣ

            Ρυθμίσεις → Εφαρμογές → AVA → Μπαταρία: χωρίς περιορισμό. Και «Κατάργηση αδειών αν δεν χρησιμοποιείται»: κλειστό.

            Αν μείνει ανοιχτή, το Android κλείνει την AVA στο παρασκήνιο και το κουμπί παύει να απαντάει. Είναι η πιο συχνή αιτία που μια σωστά εγκατεστημένη AVA «σταμάτησε».


            2) ΒΑΛΕ ΤΟ ΚΟΥΜΠΙ ΣΤΗΝ ΑΡΧΙΚΗ ΟΘΟΝΗ

            Είναι widget. Είναι ο βασικός τρόπος που χρησιμοποιείται η AVA — και μια ενημέρωση της εφαρμογής μπορεί να το αφαιρέσει. Αν χαθεί, ξαναβάλ' το.


            3) ΚΑΤΕΒΑΣΕ ΤΟ ΜΟΝΤΕΛΟ ΟΜΙΛΙΑΣ

            Μία φορά, περίπου 100 MB, με Wi-Fi. Μενού → «Λήψη μοντέλου ομιλίας».

            Χωρίς αυτό, όταν δεν υπάρχει σύνδεση η AVA δεν ακούει τίποτα. Με αυτό, δουλεύει και εντελώς χωρίς ίντερνετ.

            Δοκίμασέ το πριν φύγεις. Κλείσε τα δεδομένα και το Wi-Fi, πάτα το κουμπί και πες ένα όνομα. Κάν' το τρεις-τέσσερις φορές, όχι μία.

            Σε αργά τηλέφωνα αυτό παίρνει χρόνο — η AVA λέει «Περιμένετε» όσο σκέφτεται. Αν σου φανεί πολύ αργό, κλείσ' το: Μενού → «Λειτουργία χωρίς ίντερνετ: ΟΧΙ».

            Πρόσεξε τι σημαίνει αυτό: χωρίς σύνδεση η AVA θα το λέει και δεν θα κάνει κλήση. Δεν θα περιμένει άδικα, αλλά ούτε θα δουλεύει. Εσύ ξέρεις τι βολεύει περισσότερο αυτόν που θα τη χρησιμοποιεί.


            4) ΑΦΗΣΕ ΤΑ ΔΕΔΟΜΕΝΑ ΚΙΝΗΤΗΣ ΑΝΟΙΧΤΑ

            Χωρίς ίντερνετ δεν γίνονται κλήσεις Viber ή Signal, ούτε παίζει ραδιόφωνο. Οι κανονικές κλήσεις δουλεύουν κανονικά.

            Ο χρήστης δεν μπορεί να δει την οθόνη για να ξανανοίξει τα δεδομένα. Άφησέ τα ανοιχτά.


            5) ΑΔΕΙΕΣ

            Μικρόφωνο — για να ακούει.
            Επαφές — για να βρίσκει το όνομα.
            Κλήσεις — για να καλεί.
            Ιστορικό κλήσεων — για τις αναπάντητες.
            Εμφάνιση πάνω από άλλες εφαρμογές — για το κουμπί ακύρωσης.
            Ειδοποιήσεις.


            6) ΕΠΑΦΕΣ ΜΕ VIBER, WHATSAPP Ή SIGNAL

            Γράψε το μέσα στο όνομα της επαφής:

            Γιώργος Παπαδόπουλος VIBER

            Στο τέλος είναι το πιο καθαρό, αλλά πιάνει όπου κι αν το βάλεις — και μετά το μικρό όνομα, αν εκεί σε βόλεψε να το γράψεις. Η AVA το αφαιρεί από το όνομα και το κρατάει μόνο για να ξέρει πού να καλέσει.

            Τίποτα άλλο δεν χρειάζεται. Καμία ρύθμιση, καμία υπηρεσία προσβασιμότητας.


            ΟΙ ΕΝΤΟΛΕΣ ΕΙΝΑΙ ΣΤΑ ΕΛΛΗΝΙΚΑ

            «κλήση <όνομα>» · «ραδιόφωνο» · «φακός» · «αναπάντητες»


            ΑΝ ΚΑΤΙ ΠΑΕΙ ΣΤΡΑΒΑ

            Το γρανάζι σε αυτή την οθόνη έχει «Επαναφορά προεπιλογών». Γυρίζει τα πάντα όπως ήρθαν χωρίς να σβήσει το μοντέλο ομιλίας. Αν δεν ξέρεις τι χάλασε, ξεκίνα από εκεί.
        """.trimIndent()
    }
}
