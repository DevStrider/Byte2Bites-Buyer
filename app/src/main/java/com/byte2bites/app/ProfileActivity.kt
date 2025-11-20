package com.byte2bites.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var gestureDetector: GestureDetectorCompat

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility
    private var selectedImageUri: Uri? = null
    private val AWS_ACCESS_KEY = ""
    private val AWS_SECRET_KEY = ""
    private val S3_BUCKET_NAME = ""

    // Swipe sensitivity
    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            binding.ivProfilePicture.setImageURI(uri)
            uploadImageToS3()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Initialize gesture detector
        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        initAwsS3()
        loadUserProfile()
        setupBottomNav()

        binding.ivProfilePicture.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        binding.tvDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        binding.cardChangeUsername.setOnClickListener {
            startActivity(Intent(this, ChangeUserInfoActivity::class.java))
        }
        binding.cardAddresses.setOnClickListener {
            startActivity(Intent(this, AddressActivity::class.java))
        }
        binding.cardChangePassword.setOnClickListener {
            startActivity(Intent(this, ChangePasswordActivity::class.java))
        }
    }

    // Handle touch events for swipe gestures
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (gestureDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    // Also handle touch events on the entire layout
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { gestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    // Inner class to handle swipe gestures for ProfileActivity
    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false

            try {
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                    if (diffX > 0) {
                        // Swipe right - go to Orders
                        onSwipeRight()
                    } else {
                        // Swipe left - go to Home
                        onSwipeLeft()
                    }
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return false
        }
    }

    private fun onSwipeLeft() {
        // Navigate to Home page
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun onSwipeRight() {
        // Navigate to Orders page
        startActivity(Intent(this, OrdersActivity::class.java))
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
        }
        binding.navOrders.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        binding.navProfile.setOnClickListener {
            // already here
        }
    }

    private fun initAwsS3() {
        try {
            val awsCredentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
            s3Client = AmazonS3Client(awsCredentials, Region.getRegion(Regions.EU_NORTH_1))

            TransferNetworkLossHandler.getInstance(applicationContext)

            transferUtility = TransferUtility.builder()
                .context(applicationContext)
                .s3Client(s3Client)
                .build()
            Log.i("ProfileActivity", "AWS S3 Client Initialized.")
        } catch (e: Exception) {
            Log.e("ProfileActivity", "AWS Initialization Failed", e)
            Toast.makeText(this, "AWS Initialization Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
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
                        Glide.with(this@ProfileActivity)
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
                    this@ProfileActivity,
                    "Failed to load profile: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
    private fun uploadImageToS3() {
        val uid = auth.currentUser?.uid
        if (selectedImageUri == null || uid == null) {
            Toast.makeText(this, "No image selected or user not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(cacheDir, "${UUID.randomUUID()}.jpg")
        try {
            val inputStream = contentResolver.openInputStream(selectedImageUri!!)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to prepare image file", Toast.LENGTH_SHORT).show()
            return
        }

        val objectKey = tempFile.name
        Toast.makeText(this, "Uploading photo...", Toast.LENGTH_SHORT).show()
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

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
            }

            override fun onError(id: Int, ex: Exception) {
                Toast.makeText(
                    this@ProfileActivity,
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
                Toast.makeText(this, "Profile photo updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Failed to update profile: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(this)
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
                    Log.i("ProfileActivity", "Successfully deleted S3 photo for $uid")
                } catch (e: Exception) {
                    Log.e("ProfileActivity", "Failed to delete S3 photo for $uid", e)
                }
            }.start()

            database.reference.child("Buyers").child(uid).removeValue()
                .addOnCompleteListener { dbTask ->
                    if (dbTask.isSuccessful) {
                        user.delete().addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                Toast.makeText(
                                    this,
                                    "Account deleted successfully.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val intent = Intent(this, WelcomeActivity::class.java)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Failed to delete account: ${authTask.exception?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        Toast.makeText(
                            this,
                            "Failed to delete user data: ${dbTask.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }
}