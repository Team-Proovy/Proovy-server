package com.proovy.domain.note.service;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.request.UpdateNoteTitleRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.dto.response.DeleteNoteResponse;
import com.proovy.domain.note.dto.response.NoteDetailResponse;
import com.proovy.domain.note.dto.response.NoteListResponse;
import com.proovy.domain.note.dto.response.UpdateNoteTitleResponse;

public interface NoteService {
    CreateNoteResponse createNote(Long userId, CreateNoteRequest request);

    NoteListResponse getNoteList(Long userId, int page, int size, String sort);

    UpdateNoteTitleResponse updateNoteTitle(Long userId, Long noteId, UpdateNoteTitleRequest request);

    DeleteNoteResponse deleteNote(Long userId, Long noteId);

    NoteDetailResponse getNoteDetail(Long userId, Long noteId, int conversationPage, int conversationSize);
}

