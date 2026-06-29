package com.example.blurshieldapp.data.model

sealed class VideoEditorState {
    object Idle : VideoEditorState()
    data class Preprocessing(val progress: Int, val message: String) : VideoEditorState()
    data class Ready(val totalFrames: Int) : VideoEditorState()
    data class Error(val message: String) : VideoEditorState()
}