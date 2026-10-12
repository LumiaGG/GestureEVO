package io.github.lumiagg.gestureevo.settings.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.WebDAVSettings
import io.github.lumiagg.gestureevo.utils.WebDAVManager
import kotlinx.coroutines.launch

fun showWebDAVConfigDialog(
    activity: AppCompatActivity,
    coroutineScope: LifecycleCoroutineScope,
    onConfigured: () -> Unit
) {
    // Create dialog view
    val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_webdav_config, null)

    // Get input fields and buttons from the dialog view
    val serverUrlEditText = dialogView.findViewById<EditText>(R.id.serverUrlEditText)
    val usernameEditText = dialogView.findViewById<EditText>(R.id.usernameEditText)
    val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)
    val testButton = dialogView.findViewById<MaterialButton>(R.id.testButton)
    val saveButton = dialogView.findViewById<MaterialButton>(R.id.saveButton)
    val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
    val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)

    val webDavManager = WebDAVManager()

    // Create Material Alert Dialog
    val dialog = MaterialAlertDialogBuilder(activity)
        .setView(dialogView)
        .setCancelable(false)
        .create()

    // Load saved credentials if available
    coroutineScope.launch {
        serverUrlEditText.setText(WebDAVSettings.getServerUrl(activity))
        usernameEditText.setText(WebDAVSettings.getUsername(activity))
        passwordEditText.setText(WebDAVSettings.getPassword(activity))
    }

    testButton.setOnClickListener {
        val serverUrl = serverUrlEditText.text.toString().trim()
        val username = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (serverUrl.isEmpty() || username.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.webdav_fill_server_user),
                Toast.LENGTH_SHORT
            ).show()
            return@setOnClickListener
        }

        progressBar.visibility = View.VISIBLE
        testButton.isEnabled = false
        saveButton.isEnabled = false

        coroutineScope.launch {
            val result = webDavManager.login(serverUrl, username, password)

            progressBar.visibility = View.GONE
            testButton.isEnabled = true
            saveButton.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.webdav_connect_success),
                    Toast.LENGTH_SHORT
                ).show()
                saveButton.isEnabled = true
            } else {
                val errorMessage =
                    result.exceptionOrNull()?.localizedMessage
                        ?: activity.getString(R.string.webdav_connect_failed)
                Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    saveButton.setOnClickListener {
        val serverUrl = serverUrlEditText.text.toString().trim()
        val username = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (serverUrl.isEmpty() || username.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.webdav_fill_server_user),
                Toast.LENGTH_SHORT
            ).show()
            return@setOnClickListener
        }

        progressBar.visibility = View.VISIBLE

        coroutineScope.launch {
            WebDAVSettings.saveCredentials(activity, serverUrl, username, password)
            progressBar.visibility = View.GONE
            onConfigured()
            dialog.dismiss()
        }
    }

    cancelButton.setOnClickListener {
        dialog.dismiss()
    }

    // Show the dialog
    dialog.show()
}
