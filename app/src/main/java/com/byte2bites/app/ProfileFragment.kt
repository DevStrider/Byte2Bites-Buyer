package com.byte2bites.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Fragment responsible for displaying and managing the buyer's profile:
 * - Loads user data from Firebase Realtime Database.
 * - Displays and updates profile picture using AWS S3.
 * - Shows loyalty points and wallet credit.
 * - Provides navigation to account settings (addresses, password, user info).
 * - Handles logout and account deletion.
 */
class ProfileFragment : Fragment() {

    // ViewBinding reference for the Profile layout.
    private var _binding: ActivityProfileBinding? = null
    private val binding get() = _binding!!

    // Firebase core components for auth and realtime database access.
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    // AWS S3 client used for uploading/deleting profile images.
    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    // Holds the last image picked from gallery so it can be uploaded.
    private var selectedImageUri: Uri? = null

    // AWS credentials & bucket name placeholders (must be set per team).
    private val AWS_ACCESS_KEY = ""
    private val AWS_SECRET_KEY = ""
    private val S3_BUCKET_NAME = ""

    /**
     * Activity Result launcher to let user pick an image from gallery.
     * Once an image is selected:
     * - We show it immediately in the ImageView.
     * - Then upload it to S3 and update Firebase with the S3 URL.
     */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri //Uniform Resource Identifier
            // Show the picked image in the UI before upload completes.
            binding.ivProfilePicture.setImageURI(uri)
            uploadImageToS3()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Initialize Firebase/AWS instances and set up UI listeners.
     * This is where we:
     * - Initialize auth + database.
     * - Initialize AWS S3.
     * - Load currently logged-in user's profile.
     * - Wire click listeners for profile actions.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        initAwsS3()
        loadUserProfile()

        // Change profile picture via gallery.
        binding.ivProfilePicture.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Logout: clear Firebase Auth session and go back to WelcomeActivity.
        binding.btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }

        // Delete account permanently.
        binding.tvDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        // Navigate to change username / general user info.
        binding.cardChangeUsername.setOnClickListener {
            startActivity(Intent(requireContext(), ChangeUserInfoActivity::class.java))
        }

        // Navigate to address management (including map-based address picking).
        binding.cardAddresses.setOnClickListener {
            startActivity(Intent(requireContext(), AddressActivity::class.java))
        }

        // Navigate to change password screen.
        binding.cardChangePassword.setOnClickListener {
            startActivity(Intent(requireContext(), ChangePasswordActivity::class.java))
        }

        // Convert loyalty points to wallet credit (Milestone 4 requirement).
        binding.cardConvertPoints.setOnClickListener {
            showConvertPointsDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Avoid memory leaks by clearing reference when view is destroyed.
        _binding = null
    }

    // ==== AWS S3 init & profile loading ====

    /**
     * Initializes AWS S3 client and TransferUtility.
     * This is used later to upload/delete profile images.
     */
    private fun initAwsS3() {
        try {
            val awsCredentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
            // Create S3 client with the chosen region.
            s3Client = AmazonS3Client(awsCredentials, Region.getRegion(Regions.EU_NORTH_1))

            // Ensures uploads resume on network connectivity changes.
            TransferNetworkLossHandler.getInstance(requireContext().applicationContext)

            // High-level utility for managing uploads/downloads.
            transferUtility = TransferUtility.builder()
                .context(requireContext().applicationContext)
                .s3Client(s3Client)
                .build()
            Log.i("ProfileFragment", "AWS S3 Client Initialized.")
        } catch (e: Exception) {
            Log.e("ProfileFragment", "AWS Initialization Failed", e)
            Toast.makeText(
                requireContext(),
                "AWS Initialization Failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Loads user profile data from Firebase Realtime Database and binds it to the UI:
     * - Full name and email.
     * - Points and wallet credit.
     * - Profile photo from S3 URL if available.
     */
    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user == null) {
            // If no auth user, redirect to WelcomeActivity (defensive check).
            startActivity(Intent(requireContext(), WelcomeActivity::class.java))
            requireActivity().finish()
            return
        }

        val userRef = database.reference.child("Buyers").child(user.uid)

        userRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val userProfile = snapshot.getValue(User::class.java)
                    binding.tvUserName.text = userProfile?.fullName
                    binding.tvUserEmail.text = userProfile?.email

                    // Display points and credit values from the User node.
                    val points = userProfile?.points ?: 0
                    val credit = userProfile?.credit ?: 0
                    binding.tvPoints.text = "Points: $points"
                    binding.tvCredit.text = "Credit: ${formatCurrency(credit)}"

                    // Load profile photo from S3 link (if present), using Glide.
                    if (!userProfile?.photoUrl.isNullOrEmpty()) {
                        Glide.with(this@ProfileFragment)
                            .load(userProfile?.photoUrl)
                            .placeholder(R.drawable.ic_profile_placeholder)
                            .error(R.drawable.ic_profile_placeholder)
                            .circleCrop()
                            .into(binding.ivProfilePicture)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    requireContext(),
                    "Failed to load profile: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    /**
     * Shows a dialog allowing the user to select how many points to convert to credit.
     * - Uses SeekBar and EditText to choose a multiple of 5 points.
     * - Previews the equivalent credit using the 5 points = 1 unit (100 cents) rule.
     */
    private fun showConvertPointsDialog() {
        val user = auth.currentUser ?: return
        val userRef = database.reference.child("Buyers").child(user.uid)

        // Load current points to compute the allowed conversion range.
        userRef.get().addOnSuccessListener { snapshot ->
            val userProfile = snapshot.getValue(User::class.java)
            val availablePoints = userProfile?.points ?: 0

            if (availablePoints < 5) {
                // Minimum threshold to enable conversion.
                Toast.makeText(
                    requireContext(),
                    "You need at least 5 points to convert to credit",
                    Toast.LENGTH_LONG
                ).show()
                return@addOnSuccessListener
            }

            val maxConvertiblePoints = availablePoints
            // Conversion rule: 5 points = 1 unit of currency (100 cents)
            val dialogView = layoutInflater.inflate(R.layout.dialog_convert_points, null)
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Convert Points to Credit")
                .setView(dialogView)
                .setPositiveButton("Convert") { dialog, _ ->
                    val etPointsToConvert =
                        dialogView.findViewById<android.widget.EditText>(R.id.etPointsToConvert)
                    val pointsToConvert =
                        etPointsToConvert.text.toString().toLongOrNull() ?: 0L
                    // Conversion is done separately to reuse validation logic.
                    convertPointsToCredit(pointsToConvert)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .create()

            dialog.show()

            // Set up the dialog views
            val tvAvailablePoints =
                dialogView.findViewById<android.widget.TextView>(R.id.tvAvailablePoints)
            val tvCreditAmount =
                dialogView.findViewById<android.widget.TextView>(R.id.tvCreditAmount)
            val etPointsToConvert =
                dialogView.findViewById<android.widget.EditText>(R.id.etPointsToConvert)
            val seekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarPoints)

            tvAvailablePoints.text = "Available Points: $availablePoints"

            tvCreditAmount.text = "Credit: ${formatCurrency(0L)}"

            // SeekBar range is [0, maxConvertiblePoints].
            seekBar.max = maxConvertiblePoints.toInt()
            seekBar.progress = 0

            // Sync SeekBar and EditText values, enforcing multiples of 5 points.
            seekBar.setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    // Only multiples of 5 are allowed.
                    val points = (progress / 5) * 5
                    etPointsToConvert.setText(points.toString())

                    // Convert to cents using 5 points = 100 cents conversion.
                    val credit = (points / 5) * 100L
                    tvCreditAmount.text = "Credit: ${formatCurrency(credit)}"
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })

            // When user manually edits points in the EditText, clamp and recalc credit.
            etPointsToConvert.setOnEditorActionListener { _, _, _ ->
                val rawPoints = etPointsToConvert.text.toString().toLongOrNull() ?: 0L
                // Clamp within [0, maxConvertiblePoints] and to multiple of 5.
                val validPoints =
                    (rawPoints.coerceIn(0, maxConvertiblePoints.toLong()) / 5) * 5
                etPointsToConvert.setText(validPoints.toString())
                seekBar.progress = validPoints.toInt()

                val credit = (validPoints / 5) * 100L
                tvCreditAmount.text = "Credit: ${formatCurrency(credit)}"

                false
            }
        }
    }

    /**
     * Applies the conversion from points to credit in the database.
     * - Validates minimum points and multiple of 5 rule.
     * - Ensures user has enough points, then updates points and credit atomically.
     */
    private fun convertPointsToCredit(pointsToConvert: Long) {
        if (pointsToConvert < 5) {
            Toast.makeText(
                requireContext(),
                "Minimum 5 points required",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (pointsToConvert % 5 != 0L) {
            Toast.makeText(
                requireContext(),
                "Points must be multiples of 5",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val user = auth.currentUser ?: return
        val userRef = database.reference.child("Buyers").child(user.uid)

        userRef.get().addOnSuccessListener { snapshot ->
            val userProfile = snapshot.getValue(User::class.java)
            val availablePoints = userProfile?.points ?: 0

            if (pointsToConvert > availablePoints) {
                Toast.makeText(
                    requireContext(),
                    "Not enough points",
                    Toast.LENGTH_LONG
                ).show()
                return@addOnSuccessListener
            }

            // 5 points = 100 cents, so convert to wallet credit.
            val creditToAdd = (pointsToConvert / 5) * 100 // 5 points = 100 cents
            val newPoints = availablePoints - pointsToConvert
            val newCredit = (userProfile?.credit ?: 0) + creditToAdd

            // Partial update on "points" and "credit" fields.
            val updates = mapOf(
                "points" to newPoints,
                "credit" to newCredit
            )

            userRef.updateChildren(updates).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        requireContext(),
                        "Converted $pointsToConvert points to ${formatCurrency(creditToAdd)} credit",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Conversion failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Formats a value in cents (Long) to a currency string using the app's currency symbol.
     * Example: cents = 1500 -> "E£15.00" (depending on R.string.currency_symbol).
     */
    private fun formatCurrency(cents: Long): String {
        val symbol = getString(R.string.currency_symbol)
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$symbol$whole.$frac"
    }

    /**
     * Uploads the currently selected image to AWS S3.
     * Steps:
     * - Copies the selected Uri to a temporary File.
     * - Uploads with public read access.
     * - On success, retrieves the public URL and updates the user's "photoUrl" in Firebase.
     */
    private fun uploadImageToS3() {
        val uid = auth.currentUser?.uid
        if (selectedImageUri == null || uid == null) {
            Toast.makeText(
                requireContext(),
                "No image selected or user not logged in",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Create a temporary file in cache directory.
        val tempFile = File(requireContext().cacheDir, "${UUID.randomUUID()}.jpg")
        try {
            val inputStream =
                requireContext().contentResolver.openInputStream(selectedImageUri!!)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to prepare image file",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val objectKey = tempFile.name
        Toast.makeText(requireContext(), "Uploading photo...", Toast.LENGTH_SHORT).show()

        val transferObserver = transferUtility.upload(
            S3_BUCKET_NAME,
            objectKey,
            tempFile,
            CannedAccessControlList.PublicRead
        )

        transferObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (state == TransferState.COMPLETED) {
                    // Get the public S3 URL for this object and save to Firebase.
                    val photoUrl = s3Client.getUrl(S3_BUCKET_NAME, objectKey).toString()
                    updateFirebaseProfile(photoUrl)
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                // Could be used to show upload progress if needed.
            }

            override fun onError(id: Int, ex: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Upload Failed: ${ex.message}",
                    Toast.LENGTH_LONG
                ).show()
                tempFile.delete()
            }
        })
    }

    /**
     * Writes the given photoUrl to the "photoUrl" field under /Buyers/{uid}.
     * After success, the profile photo in the UI will update because of the ValueEventListener.
     */
    private fun updateFirebaseProfile(photoUrl: String) {
        val uid = auth.currentUser?.uid ?: return
        val userRef = database.reference.child("Buyers").child(uid)

        userRef.updateChildren(mapOf("photoUrl" to photoUrl))
            .addOnSuccessListener {
                Toast.makeText(
                    requireContext(),
                    "Profile photo updated!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    requireContext(),
                    "Failed to update profile: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Confirmation dialog shown when the user clicks "Delete Account".
     * If confirmed, triggers full account deletion: S3 photo, database record, and auth user.
     */
    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to delete your account? This action is permanent.")
            .setPositiveButton("Delete") { _, _ ->
                deleteUserAccount()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Deletes the user's S3 profile image, removes Firebase Realtime Database record,
     * and finally deletes the FirebaseAuth user.
     * This ensures account deletion is complete at all layers.
     */
    private fun deleteUserAccount() {
        val user = auth.currentUser
        val uid = user?.uid

        if (uid != null) {
            // Here we assume the objectKey is {uid}.jpg;
            // adjust if your upload naming scheme is different.
            val objectKey = "$uid.jpg"
            Thread {
                try {
                    s3Client.deleteObject(S3_BUCKET_NAME, objectKey)
                    Log.i("ProfileFragment", "Successfully deleted S3 photo for $uid")
                } catch (e: Exception) {
                    // Deleting S3 object is best-effort; it doesn't block account deletion.
                    Log.e("ProfileFragment", "Failed to delete S3 photo for $uid", e)
                }
            }.start()

            // Delete profile data from Realtime Database.
            database.reference.child("Buyers").child(uid).removeValue()
                .addOnCompleteListener { dbTask ->
                    if (dbTask.isSuccessful) {
                        // Finally delete auth user (email/password account).
                        user.delete().addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                Toast.makeText(
                                    requireContext(),
                                    "Account deleted successfully.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val intent =
                                    Intent(requireContext(), WelcomeActivity::class.java)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                requireActivity().finish()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    "Failed to delete account: ${authTask.exception?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Failed to delete user data: ${dbTask.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }
}
