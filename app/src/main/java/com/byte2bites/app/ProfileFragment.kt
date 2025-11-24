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

class ProfileFragment : Fragment() {

    private var _binding: ActivityProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility
    private var selectedImageUri: Uri? = null
    private val AWS_ACCESS_KEY = ""
    private val AWS_SECRET_KEY = ""
    private val S3_BUCKET_NAME = ""

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        initAwsS3()
        loadUserProfile()

        binding.ivProfilePicture.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }

        binding.tvDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        binding.cardChangeUsername.setOnClickListener {
            startActivity(Intent(requireContext(), ChangeUserInfoActivity::class.java))
        }
        binding.cardAddresses.setOnClickListener {
            startActivity(Intent(requireContext(), AddressActivity::class.java))
        }
        binding.cardChangePassword.setOnClickListener {
            startActivity(Intent(requireContext(), ChangePasswordActivity::class.java))
        }

        // Add points to credit conversion
        binding.cardConvertPoints.setOnClickListener {
            showConvertPointsDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==== AWS S3 init & profile loading ====

    private fun initAwsS3() {
        try {
            val awsCredentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
            s3Client = AmazonS3Client(awsCredentials, Region.getRegion(Regions.EU_NORTH_1))

            TransferNetworkLossHandler.getInstance(requireContext().applicationContext)

            transferUtility = TransferUtility.builder()
                .context(requireContext().applicationContext)
                .s3Client(s3Client)
                .build()
            Log.i("ProfileFragment", "AWS S3 Client Initialized.")
        } catch (e: Exception) {
            Log.e("ProfileFragment", "AWS Initialization Failed", e)
            Toast.makeText(requireContext(), "AWS Initialization Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user == null) {
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

                    // Display points and credit - FIXED: Convert to String
                    val points = userProfile?.points ?: 0
                    val credit = userProfile?.credit ?: 0
                    binding.tvPoints.text = "Points: $points"
                    binding.tvCredit.text = "Credit: ${formatCurrency(credit)}"

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

    private fun showConvertPointsDialog() {
        val user = auth.currentUser ?: return
        val userRef = database.reference.child("Buyers").child(user.uid)

        userRef.get().addOnSuccessListener { snapshot ->
            val userProfile = snapshot.getValue(User::class.java)
            val availablePoints = userProfile?.points ?: 0

            if (availablePoints < 5) {
                Toast.makeText(
                    requireContext(),
                    "You need at least 5 points to convert to credit",
                    Toast.LENGTH_LONG
                ).show()
                return@addOnSuccessListener
            }

            val maxConvertiblePoints = availablePoints
            val maxCredit = maxConvertiblePoints / 5 * 100 // 5 points = 1$ = 100 cents

            val dialogView = layoutInflater.inflate(R.layout.dialog_convert_points, null)
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Convert Points to Credit")
                .setView(dialogView)
                .setPositiveButton("Convert") { dialog, _ ->
                    val pointsToConvert = dialogView.findViewById<android.widget.EditText>(R.id.etPointsToConvert).text.toString().toLongOrNull() ?: 0L
                    convertPointsToCredit(pointsToConvert)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .create()

            dialog.show()

            // Set up the dialog views
            val tvAvailablePoints = dialogView.findViewById<android.widget.TextView>(R.id.tvAvailablePoints)
            val tvCreditAmount = dialogView.findViewById<android.widget.TextView>(R.id.tvCreditAmount)
            val etPointsToConvert = dialogView.findViewById<android.widget.EditText>(R.id.etPointsToConvert)
            val seekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarPoints)

            tvAvailablePoints.text = "Available Points: $availablePoints"

            seekBar.max = maxConvertiblePoints.toInt()
            seekBar.progress = 0

            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val points = (progress / 5) * 5 // Only multiples of 5
                    etPointsToConvert.setText(points.toString())
                    val credit = (points / 5) * 100 // 5 points = 100 cents (1$)
                    tvCreditAmount.text = "Credit: ${formatCurrency(credit.toLong())}"
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })

            etPointsToConvert.setOnEditorActionListener { _, _, _ ->
                val points = etPointsToConvert.text.toString().toLongOrNull() ?: 0L
                val validPoints = (points.coerceIn(0, maxConvertiblePoints) / 5) * 5
                etPointsToConvert.setText(validPoints.toString())
                seekBar.progress = validPoints.toInt()
                false
            }
        }
    }

    private fun convertPointsToCredit(pointsToConvert: Long) {
        if (pointsToConvert < 5) {
            Toast.makeText(requireContext(), "Minimum 5 points required", Toast.LENGTH_SHORT).show()
            return
        }

        if (pointsToConvert % 5 != 0L) {
            Toast.makeText(requireContext(), "Points must be multiples of 5", Toast.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser ?: return
        val userRef = database.reference.child("Buyers").child(user.uid)

        userRef.get().addOnSuccessListener { snapshot ->
            val userProfile = snapshot.getValue(User::class.java)
            val availablePoints = userProfile?.points ?: 0

            if (pointsToConvert > availablePoints) {
                Toast.makeText(requireContext(), "Not enough points", Toast.LENGTH_LONG).show()
                return@addOnSuccessListener
            }

            val creditToAdd = (pointsToConvert / 5) * 100 // 5 points = 100 cents (1$)
            val newPoints = availablePoints - pointsToConvert
            val newCredit = (userProfile?.credit ?: 0) + creditToAdd

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

    private fun formatCurrency(cents: Long): String {
        val dollars = cents / 100
        val remainingCents = cents % 100
        return "$$dollars.${remainingCents.toString().padStart(2, '0')}"
    }

    private fun uploadImageToS3() {
        val uid = auth.currentUser?.uid
        if (selectedImageUri == null || uid == null) {
            Toast.makeText(requireContext(), "No image selected or user not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(requireContext().cacheDir, "${UUID.randomUUID()}.jpg")
        try {
            val inputStream = requireContext().contentResolver.openInputStream(selectedImageUri!!)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to prepare image file", Toast.LENGTH_SHORT).show()
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
                    val photoUrl = s3Client.getUrl(S3_BUCKET_NAME, objectKey).toString()
                    updateFirebaseProfile(photoUrl)
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}

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

    private fun updateFirebaseProfile(photoUrl: String) {
        val uid = auth.currentUser?.uid ?: return
        val userRef = database.reference.child("Buyers").child(uid)

        userRef.updateChildren(mapOf("photoUrl" to photoUrl))
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Profile photo updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    requireContext(),
                    "Failed to update profile: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

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

    private fun deleteUserAccount() {
        val user = auth.currentUser
        val uid = user?.uid

        if (uid != null) {
            val objectKey = "$uid.jpg"
            Thread {
                try {
                    s3Client.deleteObject(S3_BUCKET_NAME, objectKey)
                    Log.i("ProfileFragment", "Successfully deleted S3 photo for $uid")
                } catch (e: Exception) {
                    Log.e("ProfileFragment", "Failed to delete S3 photo for $uid", e)
                }
            }.start()

            database.reference.child("Buyers").child(uid).removeValue()
                .addOnCompleteListener { dbTask ->
                    if (dbTask.isSuccessful) {
                        user.delete().addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                Toast.makeText(
                                    requireContext(),
                                    "Account deleted successfully.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val intent = Intent(requireContext(), WelcomeActivity::class.java)
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