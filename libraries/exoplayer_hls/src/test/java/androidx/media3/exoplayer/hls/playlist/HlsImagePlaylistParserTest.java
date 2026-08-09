/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls.playlist;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for parsing HLS image playlists. */
@RunWith(AndroidJUnit4.class)
public final class HlsImagePlaylistParserTest {

  private static final Uri PLAYLIST_URI = Uri.parse("https://example.com/master.m3u8");

  @Test
  public void parse_imageStreamInfBeforeStreamInf_exposesImageAfterAudioVideoVariant()
      throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"jpeg\","
            + "RESOLUTION=320x180,URI=\"images/index.m3u8\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"avc1.66.30\","
            + "RESOLUTION=1280x720\n"
            + "video/index.m3u8\n";

    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(playlistString);

    assertThat(playlist.variants).hasSize(2);
    assertThat(playlist.variants.get(0).url)
        .isEqualTo(Uri.parse("https://example.com/video/index.m3u8"));
    assertThat(playlist.variants.get(0).format.id).isEqualTo("0");
    Variant imageVariant = playlist.variants.get(1);
    assertThat(imageVariant.url).isEqualTo(Uri.parse("https://example.com/images/index.m3u8"));
    assertThat(imageVariant.format.id).isEqualTo("1");
    assertThat(imageVariant.format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_JPEG);
    assertThat(imageVariant.format.codecs).isEqualTo("jpeg");
    assertThat(imageVariant.format.peakBitrate).isEqualTo(12000);
    assertThat(imageVariant.format.width).isEqualTo(320);
    assertThat(imageVariant.format.height).isEqualTo(180);
    assertThat(imageVariant.format.roleFlags & C.ROLE_FLAG_TRICK_PLAY)
        .isEqualTo(C.ROLE_FLAG_TRICK_PLAY);
  }

  @Test
  public void parse_unsupportedImageCodec_ignoresImageVariant() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"avc1.66.30\"\n"
            + "video/index.m3u8\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"png\","
            + "RESOLUTION=320x180,URI=\"images/index.m3u8\"\n";

    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(playlistString);

    assertThat(playlist.variants).hasSize(1);
    assertThat(playlist.variants.get(0).url)
        .isEqualTo(Uri.parse("https://example.com/video/index.m3u8"));
  }

  @Test
  public void parse_imageVariantsMissingRequiredAttributes_ignoresImageVariants() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"avc1.66.30\"\n"
            + "video/index.m3u8\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,"
            + "RESOLUTION=320x180,URI=\"images/no-codecs.m3u8\"\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"jpeg\","
            + "URI=\"images/no-resolution.m3u8\"\n"
            + "#EXT-X-IMAGE-STREAM-INF:CODECS=\"jpeg\","
            + "RESOLUTION=320x180,URI=\"images/no-bandwidth.m3u8\"\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"jpeg\","
            + "RESOLUTION=320x180\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"jpeg\","
            + "RESOLUTION=320x180,URI=\"images/valid.m3u8\"\n";

    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(playlistString);

    assertThat(playlist.variants).hasSize(2);
    assertThat(playlist.variants.get(0).url)
        .isEqualTo(Uri.parse("https://example.com/video/index.m3u8"));
    assertThat(playlist.variants.get(1).url)
        .isEqualTo(Uri.parse("https://example.com/images/valid.m3u8"));
  }

  @Test
  public void parse_invalidImageVariantWithClosedCaptionsNone_hasNoClosedCaptionSideEffect()
      throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"avc1.66.30\"\n"
            + "video/index.m3u8\n"
            + "#EXT-X-IMAGE-STREAM-INF:CODECS=\"jpeg\",RESOLUTION=320x180,"
            + "URI=\"images/invalid.m3u8\",CLOSED-CAPTIONS=NONE\n";

    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(playlistString);

    assertThat(playlist.variants).hasSize(1);
    assertThat(playlist.muxedCaptionFormats).isNull();
  }

  @Test
  public void parse_imageStreamInfOnly_parsesMultivariantPlaylist() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-IMAGE-STREAM-INF:BANDWIDTH=12000,CODECS=\"jpeg\","
            + "RESOLUTION=320x180,URI=\"images/index.m3u8\"\n";

    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(playlistString);

    assertThat(playlist.variants).hasSize(1);
    assertThat(playlist.variants.get(0).format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_JPEG);
  }

  private static HlsMultivariantPlaylist parseMultivariantPlaylist(String playlistString)
      throws Exception {
    return (HlsMultivariantPlaylist)
        new HlsPlaylistParser()
            .parse(PLAYLIST_URI, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));
  }
}
