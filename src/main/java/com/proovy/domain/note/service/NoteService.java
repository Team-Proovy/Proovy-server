package com.proovy.domain.note.service;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.dto.response.NoteListResponse;

public interface NoteService {
    CreateNoteResponse createNote(Long userId, CreateNoteRequest request);

    NoteListResponse getNoteList(Long userId, int page, int size, String sort);
}

