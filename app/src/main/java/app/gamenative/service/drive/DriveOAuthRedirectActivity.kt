package app.gamenative.service.drive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import timber.log.Timber

/**
 * Trampoline that receives the browser redirect for the Drive sign-in flow
 * (`com.googleusercontent.apps.<client-id>:/oauth2redirect`).
 *
 * It hands the URI to [DriveOAuthClient] and finishes immediately, so the user
 * lands back in the app.
 */
class DriveOAuthRedirectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callback = intent?.data
        // Scrub the authorization code from the retained launch intent.
        setIntent(Intent(this, DriveOAuthRedirectActivity::class.java))

        if (callback == null) {
            Timber.w("DriveOAuthRedirectActivity: no callback URI")
        } else {
            DriveOAuthClient.onRedirect(callback)
        }
        finish()
    }
}
