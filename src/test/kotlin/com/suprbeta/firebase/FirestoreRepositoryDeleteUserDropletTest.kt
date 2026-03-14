package com.suprbeta.firebase

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.QueryDocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.WriteBatch
import com.google.cloud.firestore.WriteResult
import com.suprbeta.core.CryptoService
import io.ktor.server.application.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class FirestoreRepositoryDeleteUserDropletTest {
    private val application = mockk<Application>(relaxed = true)

    @Test
    fun `deleteUserDroplet deletes droplet docs and chat history subtree`() {
        val firestore = mockk<Firestore>()
        val usersCollection = mockk<CollectionReference>()
        val userDoc = mockk<DocumentReference>()
        val dropletsCollection = mockk<CollectionReference>()
        val chatThreadsCollection = mockk<CollectionReference>()
        val dropletQuery = mockk<QuerySnapshot>()
        val threadQuery = mockk<QuerySnapshot>()
        val messagesQuery = mockk<QuerySnapshot>()
        val dropletDoc = mockk<QueryDocumentSnapshot>()
        val threadDoc = mockk<QueryDocumentSnapshot>()
        val messageDoc1 = mockk<QueryDocumentSnapshot>()
        val messageDoc2 = mockk<QueryDocumentSnapshot>()
        val dropletDocRef = mockk<DocumentReference>()
        val threadDocRef = mockk<DocumentReference>()
        val messageDocRef1 = mockk<DocumentReference>()
        val messageDocRef2 = mockk<DocumentReference>()
        val messagesCollection = mockk<CollectionReference>()
        val messageBatch = mockk<WriteBatch>()
        val threadBatch = mockk<WriteBatch>()

        every { firestore.collection("users") } returns usersCollection
        every { usersCollection.document("user-1") } returns userDoc
        every { userDoc.collection("droplets") } returns dropletsCollection
        every { userDoc.collection("chat_threads") } returns chatThreadsCollection
        every { dropletsCollection.get() } returns future(dropletQuery)
        every { chatThreadsCollection.get() } returns future(threadQuery)
        every { dropletQuery.documents } returns listOf(dropletDoc)
        every { threadQuery.documents } returns listOf(threadDoc)
        every { dropletDoc.id } returns "99"
        every { dropletsCollection.document("99") } returns dropletDocRef
        every { dropletDocRef.delete() } returns future(mockk())
        every { threadDoc.reference } returns threadDocRef
        every { threadDocRef.collection("messages") } returns messagesCollection
        every { messagesCollection.get() } returns future(messagesQuery)
        every { messagesQuery.documents } returns listOf(messageDoc1, messageDoc2)
        every { messageDoc1.reference } returns messageDocRef1
        every { messageDoc2.reference } returns messageDocRef2

        every { firestore.batch() } returnsMany listOf(messageBatch, threadBatch)
        every { messageBatch.delete(messageDocRef1) } returns messageBatch
        every { messageBatch.delete(messageDocRef2) } returns messageBatch
        every { threadBatch.delete(threadDocRef) } returns threadBatch
        every { messageBatch.commit() } returns future(emptyList<WriteResult>())
        every { threadBatch.commit() } returns future(emptyList<WriteResult>())

        val repository = FirestoreRepository(
            firestore = firestore,
            application = application,
            cryptoService = CryptoService(application, CryptoService.generateNewKeyset())
        )

        runBlocking {
            repository.deleteUserDroplet("user-1")
        }

        verify(exactly = 1) { dropletDocRef.delete() }
        verify(exactly = 1) { messageBatch.delete(messageDocRef1) }
        verify(exactly = 1) { messageBatch.delete(messageDocRef2) }
        verify(exactly = 1) { threadBatch.delete(threadDocRef) }
        verify(exactly = 1) { messageBatch.commit() }
        verify(exactly = 1) { threadBatch.commit() }
    }

    private fun <T> future(value: T): ApiFuture<T> {
        val future = mockk<ApiFuture<T>>()
        every { future.get() } returns value
        return future
    }
}
