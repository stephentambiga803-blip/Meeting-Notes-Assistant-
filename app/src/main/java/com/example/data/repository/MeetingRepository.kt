package com.example.data.repository

import com.example.data.database.Meeting
import com.example.data.database.MeetingDao
import kotlinx.coroutines.flow.Flow

class MeetingRepository(private val meetingDao: MeetingDao) {
    val allMeetings: Flow<List<Meeting>> = meetingDao.getAllMeetings()

    fun searchMeetings(query: String): Flow<List<Meeting>> = meetingDao.searchMeetings(query)

    suspend fun getMeetingById(id: Int): Meeting? = meetingDao.getMeetingById(id)

    fun getMeetingByIdFlow(id: Int): Flow<Meeting?> = meetingDao.getMeetingByIdFlow(id)

    suspend fun insertMeeting(meeting: Meeting): Long = meetingDao.insertMeeting(meeting)

    suspend fun updateMeeting(meeting: Meeting) = meetingDao.updateMeeting(meeting)

    suspend fun deleteMeeting(meeting: Meeting) = meetingDao.deleteMeeting(meeting)
}
