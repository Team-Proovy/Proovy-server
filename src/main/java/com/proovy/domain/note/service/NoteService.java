package com.proovy.domain.note.service;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;

public interface NoteService {
    CreateNoteResponse createNote(Long userId, CreateNoteRequest request);
}

