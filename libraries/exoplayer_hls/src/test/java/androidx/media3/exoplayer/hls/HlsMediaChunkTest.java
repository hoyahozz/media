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
package androidx.media3.exoplayer.hls;

import static androidx.media3.exoplayer.source.SampleStream.FLAG_OMIT_SAMPLE_DATA;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link HlsMediaChunk}. */
@RunWith(AndroidJUnit4.class)
public final class HlsMediaChunkTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HlsMediaChunkExtractor mockExtractor;
  @Mock private HlsExtractorFactory extractorFactory;

  @Before
  public void setUp() throws Exception {
    when(mockExtractor.isReusable()).thenReturn(true);
    when(extractorFactory.createExtractor(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(mockExtractor);
  }

  @Test
  public void load_extractorThrowsEOFExceptionAfterFullyLoadedChunk_throwsParserException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              ExtractorInput input = invocation.getArgument(0);
              input.skipFully(100);
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ 100, // Explicit finite length
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    ParserException exception = assertThrows(ParserException.class, mediaChunk::load);
    assertThat(exception).hasCauseThat().isInstanceOf(EOFException.class);
  }

  @Test
  public void load_extractorThrowsEOFExceptionBeforeFullyLoadedChunk_propagatesEOFException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              ExtractorInput input = invocation.getArgument(0);
              input.skipFully(50); // Premature read (less than segment's 100 length)
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ 100,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    assertThrows(EOFException.class, mediaChunk::load);
  }

  @Test
  public void load_extractorThrowsEOFExceptionForUnsetLengthChunk_propagatesEOFException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET, // Unset length
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    assertThrows(EOFException.class, mediaChunk::load);
  }

  @Test
  public void load_imageGrid_setsSegmentOffsetAndWritesSamplesWithinSegmentBoundary()
      throws Exception {
    HlsMediaPlaylist.ImageInfo imageInfo =
        new HlsMediaPlaylist.ImageInfo(
            /* tileWidth= */ 160,
            /* tileHeight= */ 90,
            /* tileCountHorizontal= */ 3,
            /* tileCountVertical= */ 2,
            /* tileDurationUs= */ 1_000_000L);
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/grid.jpg",
            /* initializationSegment= */ null,
            /* title= */ "",
            /* durationUs= */ 4_500_000L,
            /* relativeDiscontinuitySequence= */ 0,
            /* relativeStartTimeUs= */ 2_000_000L,
            /* drmInitData= */ null,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null,
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET,
            /* hasGapTag= */ false,
            /* parts= */ ImmutableList.of(),
            imageInfo);
    Format imageFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_JPEG)
            .setRoleFlags(C.ROLE_FLAG_TRICK_PLAY)
            .build();
    TrackOutput[] imageTrackOutput = new TrackOutput[1];
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              TrackOutput trackOutput = extractorOutput.track(/* id= */ 7, C.TRACK_TYPE_IMAGE);
              imageTrackOutput[0] = trackOutput;
              trackOutput.format(
                  imageFormat
                      .buildUpon()
                      .setWidth(160)
                      .setHeight(90)
                      .setTileCountHorizontal(3)
                      .setTileCountVertical(2)
                      .build());
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    when(mockExtractor.read(any()))
        .thenAnswer(
            invocation -> {
              imageTrackOutput[0].sampleData(new ParsableByteArray(), /* length= */ 0);
              imageTrackOutput[0].sampleMetadata(
                  /* timeUs= */ 0,
                  C.BUFFER_FLAG_KEY_FRAME,
                  /* size= */ 0,
                  /* offset= */ 0,
                  /* cryptoData= */ null);
              return false;
            });
    HlsSampleStreamWrapper output = createSampleStreamWrapper(C.TRACK_TYPE_IMAGE);
    TimestampAdjusterProvider timestampAdjusterProvider = new TimestampAdjusterProvider();
    HlsMediaChunk mediaChunk =
        createChunkWithSegment(
            extractorFactory,
            segment,
            imageFormat,
            output,
            /* isPrimaryTimestampSource= */ true,
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            timestampAdjusterProvider);
    output.initMediaChunkLoad(mediaChunk);

    mediaChunk.load();

    assertThat(timestampAdjusterProvider.getAdjuster(/* discontinuitySequence= */ 0).isInitialized())
        .isFalse();

    ArgumentCaptor<Format> extractorFormatCaptor = ArgumentCaptor.forClass(Format.class);
    verify(extractorFactory)
        .createExtractor(any(), extractorFormatCaptor.capture(), any(), any(), any(), any(), any());
    Format extractorFormat = extractorFormatCaptor.getValue();
    assertThat(extractorFormat.width).isEqualTo(160);
    assertThat(extractorFormat.height).isEqualTo(90);
    assertThat(extractorFormat.tileCountHorizontal).isEqualTo(3);
    assertThat(extractorFormat.tileCountVertical).isEqualTo(2);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    assertThat(
            output.readData(
                /* sampleQueueIndex= */ 0,
                formatHolder,
                buffer,
                /* readFlags= */ FLAG_OMIT_SAMPLE_DATA))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format.tileCountHorizontal).isEqualTo(3);
    assertThat(formatHolder.format.tileCountVertical).isEqualTo(2);
    ImmutableList.Builder<Long> sampleTimesUs = ImmutableList.builder();
    for (int i = 0; i < 5; i++) {
      assertThat(
              output.readData(
                  /* sampleQueueIndex= */ 0,
                  formatHolder,
                  buffer,
                  /* readFlags= */ FLAG_OMIT_SAMPLE_DATA))
          .isEqualTo(C.RESULT_BUFFER_READ);
      sampleTimesUs.add(buffer.timeUs);
      buffer.clear();
    }
    assertThat(sampleTimesUs.build())
        .containsExactly(2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L)
        .inOrder();
    assertThat(
            output.readData(
                /* sampleQueueIndex= */ 0,
                formatHolder,
                buffer,
                /* readFlags= */ FLAG_OMIT_SAMPLE_DATA))
        .isEqualTo(C.RESULT_NOTHING_READ);
  }

  @Test
  public void load_secondaryImage_doesNotWaitForTimestampAdjuster() throws Exception {
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/image.jpg",
            /* initializationSegment= */ null,
            /* title= */ "",
            /* durationUs= */ 1_000_000L,
            /* relativeDiscontinuitySequence= */ 0,
            /* relativeStartTimeUs= */ 0,
            /* drmInitData= */ null,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null,
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET,
            /* hasGapTag= */ false,
            /* parts= */ ImmutableList.of(),
            /* imageInfo= */ null);
    Format imageFormat = new Format.Builder().setSampleMimeType(MimeTypes.IMAGE_JPEG).build();
    when(mockExtractor.read(any())).thenReturn(false);
    HlsMediaChunk mediaChunk =
        createChunkWithSegment(
            extractorFactory,
            segment,
            imageFormat,
            createSampleStreamWrapper(C.TRACK_TYPE_IMAGE),
            /* isPrimaryTimestampSource= */ false,
            /* timestampAdjusterInitializationTimeoutMs= */ 1);

    mediaChunk.load();
  }

  private static HlsMediaChunk createChunkWithSegment(
      HlsExtractorFactory extractorFactory, HlsMediaPlaylist.Segment segment) throws IOException {
    return createChunkWithSegment(
        extractorFactory,
        segment,
        new Format.Builder().build(),
        createSampleStreamWrapper(C.TRACK_TYPE_VIDEO));
  }

  private static HlsSampleStreamWrapper createSampleStreamWrapper(@C.TrackType int trackType) {
    return new HlsSampleStreamWrapper(
        /* uid= */ "",
        trackType,
        mock(HlsSampleStreamWrapper.Callback.class),
        mock(HlsChunkSource.class),
        /* overridingDrmInitData= */ ImmutableMap.of(),
        mock(Allocator.class),
        /* positionUs= */ 0,
        /* muxedAudioFormat= */ null,
        mock(DrmSessionManager.class),
        mock(DrmSessionEventListener.EventDispatcher.class),
        mock(LoadErrorHandlingPolicy.class),
        mock(MediaSourceEventListener.EventDispatcher.class),
        /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
        /* downloadExecutor= */ null);
  }

  private static HlsMediaChunk createChunkWithSegment(
      HlsExtractorFactory extractorFactory,
      HlsMediaPlaylist.Segment segment,
      Format format,
      HlsSampleStreamWrapper output)
      throws IOException {
    return createChunkWithSegment(
        extractorFactory,
        segment,
        format,
        output,
        /* isPrimaryTimestampSource= */ true,
        /* timestampAdjusterInitializationTimeoutMs= */ 0);
  }

  private static HlsMediaChunk createChunkWithSegment(
      HlsExtractorFactory extractorFactory,
      HlsMediaPlaylist.Segment segment,
      Format format,
      HlsSampleStreamWrapper output,
      boolean isPrimaryTimestampSource,
      long timestampAdjusterInitializationTimeoutMs)
      throws IOException {
    return createChunkWithSegment(
        extractorFactory,
        segment,
        format,
        output,
        isPrimaryTimestampSource,
        timestampAdjusterInitializationTimeoutMs,
        new TimestampAdjusterProvider());
  }

  private static HlsMediaChunk createChunkWithSegment(
      HlsExtractorFactory extractorFactory,
      HlsMediaPlaylist.Segment segment,
      Format format,
      HlsSampleStreamWrapper output,
      boolean isPrimaryTimestampSource,
      long timestampAdjusterInitializationTimeoutMs,
      TimestampAdjusterProvider timestampAdjusterProvider)
      throws IOException {
    HlsMediaPlaylist mediaPlaylist =
        new HlsMediaPlaylist(
            HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
            /* baseUri= */ "http://example.com/",
            /* tags= */ ImmutableList.of(),
            /* startOffsetUs= */ C.TIME_UNSET,
            /* preciseStart= */ false,
            /* startTimeUs= */ 0L,
            /* hasDiscontinuitySequence= */ false,
            /* discontinuitySequence= */ 0,
            /* mediaSequence= */ 0L,
            /* version= */ 7,
            /* targetDurationUs= */ 4_000_000L,
            /* partTargetDurationUs= */ C.TIME_UNSET,
            /* hasIndependentSegments= */ true,
            /* hasEndTag= */ false,
            /* hasProgramDateTime= */ false,
            /* protectionSchemes= */ null,
            /* segments= */ ImmutableList.of(segment),
            /* trailingParts= */ ImmutableList.of(),
            new HlsMediaPlaylist.ServerControl(
                /* skipUntilUs= */ C.TIME_UNSET,
                /* canSkipDateRanges= */ false,
                /* holdBackUs= */ C.TIME_UNSET,
                /* partHoldBackUs= */ C.TIME_UNSET,
                /* canBlockReload= */ false),
            /* renditionReports= */ ImmutableMap.of(),
            /* interstitials= */ ImmutableList.of(),
            /* lastSeenInitSegment= */ null);
    FakeDataSource fakeDataSource = new FakeDataSource();
    fakeDataSource.getDataSet().newDefaultData().appendReadData(100).endData();
    HlsMediaChunk mediaChunk =
        HlsMediaChunk.createInstance(
            extractorFactory,
            fakeDataSource,
            format,
            /* startOfPlaylistInPeriodUs= */ 0,
            mediaPlaylist,
            new HlsChunkSource.SegmentBaseHolder(
                segment, /* mediaSequence= */ 1, /* partIndex= */ 0),
            Uri.parse("https://playlist.uri/"),
            /* steeredPathwayId= */ null,
            /* muxedCaptionFormats= */ null,
            C.SELECTION_REASON_UNKNOWN,
            /* trackSelectionData= */ null,
            isPrimaryTimestampSource,
            timestampAdjusterProvider,
            timestampAdjusterInitializationTimeoutMs,
            /* previousChunk= */ null,
            /* mediaSegmentKey= */ null,
            /* initSegmentKey= */ null,
            /* shouldSpliceIn= */ false,
            /* isIndependent= */ true,
            PlayerId.UNSET,
            /* cmcdDataFactory= */ null);
    mediaChunk.init(output, ImmutableList.of());
    return mediaChunk;
  }
}
