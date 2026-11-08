// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView =
            TextView(this).apply {
                text = getString(R.string.app_name)
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(getColor(R.color.brand_primary))
            }
        setContentView(textView)
    }
}
