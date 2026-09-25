package com.rudra.timbreminiapp

import com.rudra.timbreminiapp.trimmer.TrimError
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun `typed trim errors map to friendly resources`() {
        assertEquals(R.string.error_read_failed, TrimError.UnreadableSource().toUserMessageRes())
        assertEquals(R.string.error_min_length, TrimError.InvalidRange().toUserMessageRes())
        assertEquals(R.string.error_unsupported, TrimError.ConversionFailed(null).toUserMessageRes())
        assertEquals(R.string.error_empty_output, TrimError.EmptyOutput().toUserMessageRes())
        assertEquals(R.string.error_storage_write, TrimError.StorageWriteFailed().toUserMessageRes())
        assertEquals(R.string.error_cancelled, TrimError.Cancelled().toUserMessageRes())
    }

    @Test
    fun `unknown throwables fall back to a generic message`() {
        assertEquals(R.string.error_generic, IllegalStateException("boom").toUserMessageRes())
    }
}