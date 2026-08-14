package de.lino.database.utils.export.transcript.format;

import de.lino.database.utils.export.transcript.TranscriptExporter;

import java.util.Objects;

/**
 * A page's size and orientation, e.g. A4 portrait, passed to
 * {@link TranscriptExporter#export} so a caller can choose how a transcript is laid
 * out rather than either implementation hardcoding a single page size.
 *
 * @param format the page's ISO 216 size
 * @param orientation the page's orientation
 */
public record PageLayout(PageFormat format, PageOrientation orientation) {

    /**
     * The layout every export used before {@link PageLayout} existed: A4 portrait.
     */
    public static final PageLayout DEFAULT = new PageLayout(PageFormat.A4, PageOrientation.PORTRAIT);

    public PageLayout {
        Objects.requireNonNull(format, "@PageLayout: format must not be null");
        Objects.requireNonNull(orientation, "@PageLayout: orientation must not be null");
    }

}
