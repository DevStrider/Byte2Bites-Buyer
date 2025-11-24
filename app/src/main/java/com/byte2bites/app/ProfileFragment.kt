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
