package com.socatv.nova.ui.profiles

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.socatv.nova.R
import com.socatv.nova.data.model.Profile
import com.socatv.nova.data.repository.ProfileRepository
import com.socatv.nova.databinding.ActivityProfilesBinding
import com.socatv.nova.databinding.ItemProfileBinding
import com.socatv.nova.ui.home.HomeActivity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.setFocusBorder

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var profileRepo: ProfileRepository
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileRepo = ProfileRepository()

        setupRecyclerView()
        loadProfiles()

        binding.btnAddProfile.setOnClickListener { showAddProfileDialog() }
        binding.btnAddProfile.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                .scaleY(if (hasFocus) 1.08f else 1f).setDuration(150).start()
            v.setFocusBorder(hasFocus)
        }
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(
            onSelect = { profile -> selectProfile(profile) },
            onEdit = { profile -> showEditProfileDialog(profile) },
            onDelete = { profile -> confirmDeleteProfile(profile) }
        )
        binding.rvProfiles.layoutManager = GridLayoutManager(this, 4)
        binding.rvProfiles.adapter = adapter
    }

    private fun loadProfiles() {
        val profiles = profileRepo.getAllProfiles()
        adapter.submitList(profiles)
        binding.btnAddProfile.visibility = if (profiles.size < 4) View.VISIBLE else View.GONE
    }

    private fun selectProfile(profile: Profile) {
        if (profile.isPinProtected) {
            showPinDialog(profile)
        } else {
            launchHome(profile)
        }
    }

    private fun showPinDialog(profile: Profile) {
        val input = android.widget.EditText(this).apply {
            hint = "Enter 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Enter PIN for ${profile.name}")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (profileRepo.verifyPin(profile.id, entered)) {
                    launchHome(profile)
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchHome(profile: Profile) {
        profileRepo.setActiveProfile(profile.id)
        Prefs.activeProfileId = profile.id
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun showAddProfileDialog() {
        val profiles = profileRepo.getAllProfiles()
        if (profiles.size >= 4) {
            Toast.makeText(this, "Maximum 4 profiles reached", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Profile name"
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Create Profile")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotBlank()) {
                    val result = profileRepo.createProfile(name, avatarIndex = profiles.size)
                    result.fold(
                        onSuccess = { loadProfiles() },
                        onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditProfileDialog(profile: Profile) {
        val input = android.widget.EditText(this).apply {
            setText(profile.name)
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Edit Profile")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotBlank()) {
                    profileRepo.updateProfile(profile.copy(name = name))
                    loadProfiles()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteProfile(profile: Profile) {
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Delete Profile")
            .setMessage("Delete ${profile.name}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (profileRepo.deleteProfile(profile.id)) {
                    loadProfiles()
                } else {
                    Toast.makeText(this, "Cannot delete last profile", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class ProfileAdapter(
    private val onSelect: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    private var profiles: List<Profile> = emptyList()

    fun submitList(list: List<Profile>) {
        profiles = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount() = profiles.size

    inner class VH(private val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: Profile) {
            binding.tvName.text = profile.name
            binding.ivKidsMode.visibility = if (profile.isKidsMode) View.VISIBLE else View.GONE
            binding.ivPinIcon.visibility = if (profile.isPinProtected) View.VISIBLE else View.GONE

            // Avatar color based on index
            val colors = listOf(
                0xFF00DCFF.toInt(), 0xFFDC143C.toInt(), 0xFF7CFC00.toInt(),
                0xFFFFD700.toInt(), 0xFFFF69B4.toInt(), 0xFF9400D3.toInt(),
                0xFFFF8C00.toInt(), 0xFF00FA9A.toInt()
            )
            val color = colors[profile.avatarIndex % colors.size]
            binding.ivAvatar.setBackgroundColor(color)
            binding.tvAvatarLetter.text = profile.name.first().uppercaseChar().toString()

            binding.root.setOnClickListener { onSelect(profile) }
            binding.root.setOnLongClickListener {
                showContextMenu(profile)
                true
            }
            binding.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.1f else 1f)
                    .scaleY(if (hasFocus) 1.1f else 1f).setDuration(150).start()
                binding.focusRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                v.setFocusBorder(hasFocus)
            }
        }

        private fun showContextMenu(profile: Profile) {
            val options = arrayOf("Edit", "Delete")
            AlertDialog.Builder(binding.root.context, R.style.NovaDialogTheme)
                .setTitle(profile.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> onEdit(profile)
                        1 -> onDelete(profile)
                    }
                }.show()
        }
    }
}
