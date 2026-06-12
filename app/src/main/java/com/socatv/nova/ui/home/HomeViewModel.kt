package com.socatv.nova.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socatv.nova.NovaApp
import com.socatv.nova.data.model.Category
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.TmdbItem
import com.socatv.nova.data.model.WatchHistory
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.utils.Prefs
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val repository = IptvRepository(NovaApp.instance.database)

    private val _liveCategories = MutableLiveData<List<Category>>()
    val liveCategories: LiveData<List<Category>> = _liveCategories

    private val _trending = MutableLiveData<List<TmdbItem>>()
    val trending: LiveData<List<TmdbItem>> = _trending

    private val _fanartUrl = MutableLiveData<String?>()
    val fanartUrl: LiveData<String?> = _fanartUrl

    /** Full list of backdrop URLs for the rotating slideshow (up to 12 images). */
    private val _fanartUrls = MutableLiveData<List<String>>()
    val fanartUrls: LiveData<List<String>> = _fanartUrls

    private val _liveNow = MutableLiveData<List<Channel>>()
    val liveNow: LiveData<List<Channel>> = _liveNow

    private val _continueWatching = MutableLiveData<List<WatchHistory>>()
    val continueWatching: LiveData<List<WatchHistory>> = _continueWatching

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            loadTrending()
            loadLiveCategories()
            loadLiveNow()
            loadContinueWatching()
            _isLoading.value = false
        }
    }

    private suspend fun loadTrending() {
        repository.getTrending().fold(
            onSuccess = { items ->
                _trending.value = items.take(20)
                val backdrops = items
                    .filter { !it.backdropPath.isNullOrBlank() }
                    .take(12)
                    .map { "https://image.tmdb.org/t/p/w1280${it.backdropPath}" }
                _fanartUrls.value = backdrops
                _fanartUrl.value  = backdrops.firstOrNull()
            },
            onFailure = { /* silent */ }
        )
    }

    private suspend fun loadLiveCategories() {
        repository.getLiveCategories().fold(
            onSuccess = { _liveCategories.value = it },
            onFailure = { _errorMessage.value = "Could not load categories: ${it.message}" }
        )
    }

    private suspend fun loadLiveNow() {
        repository.getLiveStreams().fold(
            onSuccess = { channels ->
                _liveNow.value = channels.take(20)
            },
            onFailure = { /* silent */ }
        )
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            repository.getContinueWatching(Prefs.activeProfileId).collect { history ->
                _continueWatching.value = history
            }
        }
    }

    fun refreshFanart() {
        viewModelScope.launch {
            val url = repository.getTrendingBackdrop()
            _fanartUrl.value = url
        }
    }
}
