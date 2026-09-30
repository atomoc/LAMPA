(function () {
    // Display-only snapshot of Lampa's HTML player. No timers or event handlers in the page.
    function text(value, limit) {
        return typeof value === 'string' ? value.replace(/[\x00-\x1f\x7f]/g, ' ').trim().slice(0, limit) : '';
    }
    function number(value) { return typeof value === 'number' && isFinite(value) && value >= 0 ? value : 0; }
    try {
        var lampa = window.Lampa;
        if (!lampa || !lampa.Player || !lampa.Player.opened || !lampa.Player.opened()) return null;
        var video = lampa.PlayerVideo && lampa.PlayerVideo.video ? lampa.PlayerVideo.video() : null;
        if (!video || video.tagName !== 'VIDEO') video = document.querySelector('video.player-video__video');
        if (!video || !video.getClientRects().length || !(video.currentSrc || video.src)) return null;
        var work = lampa.Player.playdata ? lampa.Player.playdata() || {} : {};
        var active = lampa.Activity && lampa.Activity.active ? lampa.Activity.active() || {} : {};
        var movie = active.movie || {};
        var series = text(movie.title || movie.name, 250);
        var streamTitle = text(work.title || work.name, 250);
        var label = document.querySelector('.player-info__title');
        var uiTitle = text(label && label.textContent, 250);
        // playdata.title can be a stream/audio label (for example "Русский"), not content.
        // Prefer Lampa's visible player title. Without it, only treat playdata as content
        // when it structurally looks like an episode/season label; otherwise use the card.
        var episodeTitle = /(?:^|\s)(?:серия|сезон|episode|season)\s*\d+|\bS\d+E\d+\b|\b\d+\s*[xх]\s*\d+\b/i.test(streamTitle) ? streamTitle : '';
        var title = uiTitle || episodeTitle || series || streamTitle;
        var subtitle = series && series !== title ? series : '';
        // A portrait poster fits the phone without enlarging a narrow landscape crop.
        var tmdbPath = text(movie.poster_path || movie.backdrop_path, 1024);
        var artwork = tmdbPath ? '' : text(work.poster || work.thumbnail || work.img ||
            movie.poster || movie.img || movie.background_image || video.poster, 4096);
        if (/\/img\/video_poster\./i.test(artwork)) artwork = '';
        // A non-secret identifier; the stream URL itself is never sent to Android's media session.
        var source = String(video.currentSrc || video.src), hash = 0;
        for (var i = 0; i < source.length; i++) hash = (hash * 31 + source.charCodeAt(i)) | 0;
        var state = video.error ? 7 : video.ended ? 1 : video.paused ? 2 : video.readyState < 3 ? 6 : 3;
        return JSON.stringify({key: String(hash), title: title, subtitle: subtitle,
            artwork: artwork, tmdbPath: artwork ? '' : tmdbPath, state: state,
            position: number(video.currentTime), duration: number(video.duration),
            rate: state === 3 ? number(video.playbackRate) : 0,
            seekable: !!(video.seekable && video.seekable.length)});
    } catch (error) { return null; }
})()
