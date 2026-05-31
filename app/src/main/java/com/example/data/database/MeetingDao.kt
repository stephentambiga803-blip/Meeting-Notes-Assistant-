package com.example.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY timestamp DESC")
    fun getAllMeetings(): Flow<List<Meeting>>

    @Query("SELECT * FROM meetings WHERE title LIKE '%' || :query || '%' OR transcript LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR actionItems LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMeetings(query: String): Flow<List<Meeting>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getMeetingById(id: Int): Meeting?

    @Query("SELECT * FROM meetings WHERE id = :id")
    fun getMeetingByIdFlow(id: Int): Flow<Meeting?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeeting(meeting: Meeting): Long

    @Update
    suspend fun updateMeeting(meeting: Meeting)

    @Delete
    suspend fun deleteMeeting(meeting: Meeting)
}
