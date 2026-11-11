package app.fluxo.financas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.KnownApps
import app.fluxo.financas.data.Prefs
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.FragmentSettingsBinding
import app.fluxo.financas.util.CsvExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(R.layout.fragment_settings), Refreshable {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private val prefs by lazy { Prefs(requireContext()) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)

        binding.autoImportSwitch.isChecked = prefs.autoImport
        binding.autoImportSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoImport = checked
        }
        binding.onlyBanksSwitch.isChecked = prefs.onlyBanks
        binding.onlyBanksSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.onlyBanks = checked
        }

        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.appsRow.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.apps_title)
                .setMessage(R.string.apps_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        binding.exportRow.setOnClickListener { exportCsv() }
        binding.seedRow.setOnClickListener { confirmSeed() }
        binding.wipeRow.setOnClickListener { confirmWipe() }

        updateAccess()
    }

    override fun onResume() {
        super.onResume()
        updateAccess()
        maybeAskNotificationPermission()
    }

    private fun updateAccess() {
        if (_binding == null) return
        val granted = prefs.hasNotificationAccess(requireContext())
        binding.accessStatus.text =
            getString(if (granted) R.string.settings_access_on else R.string.settings_access_off)
        binding.accessStatus.setTextColor(
            ContextCompat.getColor(requireContext(), if (granted) R.color.income else R.color.ink_soft)
        )
        binding.accessButton.text =
            getString(if (granted) R.string.settings_access_on else R.string.settings_access)
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!prefs.hasNotificationAccess(requireContext())) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun exportCsv() {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                CsvExporter.export(requireContext(), repo.allTransactions())
            }
            CsvExporter.share(requireContext(), file)
        }
    }

    private fun confirmSeed() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.seed_confirm)
            .setMessage(R.string.seed_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.load) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.seedDemo()
                    (activity as? MainActivity)?.refreshAll()
                    Toast.makeText(requireContext(), R.string.added, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmWipe() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wipe_title)
            .setMessage(R.string.settings_about_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wipe) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.wipe()
                    (activity as? MainActivity)?.refreshAll()
                    Toast.makeText(requireContext(), R.string.removed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun refreshData() {
        updateAccess()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
