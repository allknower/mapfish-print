package org.mapfish.print.map.tiled;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.mapfish.print.ExceptionUtils;
import org.mapfish.print.FloatingPointUtil;
import org.mapfish.print.attribute.map.MapBounds;
import org.mapfish.print.attribute.map.MapfishMapContext;
import org.mapfish.print.http.HttpRequestCache;
import org.mapfish.print.http.MfClientHttpRequestFactory;
import org.mapfish.print.map.tiled.TilePreparationInfo.SingleTilePreparationInfo;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequest;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.Nonnull;


/**
 * The Tile Preparation Task class.
 */
public final class TilePreparationTask implements Callable<TilePreparationInfo> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TilePreparationTask.class);

    private final MapBounds bounds;
    private final Rectangle paintArea;
    private final MapfishMapContext transformer;
    private final TileCacheInformation tiledLayer;
    private final String jobId;
    private final MfClientHttpRequestFactory httpRequestFactory;
    private final HttpRequestCache requestCache;
    private Optional<Geometry> cachedRotatedMapBounds = null;

    /**
     * Constructor.
     *
     * @param httpRequestFactory the factory to use for making http requests
     * @param transformer a transformer for making calculations
     * @param tileCacheInfo the object used to create the tile requests
     * @param requestCache request cache
     * @param jobId the job ID
     */
    public TilePreparationTask(
            @Nonnull final MfClientHttpRequestFactory httpRequestFactory,
            @Nonnull final MapfishMapContext transformer,
            @Nonnull final TileCacheInformation tileCacheInfo,
            final HttpRequestCache requestCache,
            @Nonnull final String jobId) {
        this.requestCache = requestCache;
        this.bounds = transformer.getBounds();
        this.paintArea = new Rectangle(transformer.getMapSize());
        this.httpRequestFactory = httpRequestFactory;
        this.transformer = transformer;
        this.tiledLayer = tileCacheInfo;
        this.jobId = jobId;
    }

    /**
     * Call the Tile Preparation Task.
     */
    public TilePreparationInfo call() {
        try {
            MDC.put("job_id", this.jobId);
            final ReferencedEnvelope mapGeoBounds = this.bounds.toReferencedEnvelope(this.paintArea);
            final CoordinateReferenceSystem mapProjection = mapGeoBounds.getCoordinateReferenceSystem();
            Dimension tileSizeOnScreen = this.tiledLayer.getTileSize();

            final double layerResolution = this.tiledLayer.getResolution();
            Coordinate tileSizeInWorld = new Coordinate(tileSizeOnScreen.width * layerResolution,
                                                        tileSizeOnScreen.height * layerResolution);

            // The minX minY of the first (minY, minY) tile
            Coordinate gridCoverageOrigin =
                    this.tiledLayer.getMinGeoCoordinate(mapGeoBounds, tileSizeInWorld);

            final String commonUrl = this.tiledLayer.createCommonUrl();

            ReferencedEnvelope tileCacheBounds = this.tiledLayer.getTileCacheBounds();
            final double resolution = this.tiledLayer.getResolution();
            double rowFactor = 1 / (resolution * tileSizeOnScreen.height);
            double columnFactor = 1 / (resolution * tileSizeOnScreen.width);

            int imageWidth = 0;
            int imageHeight = 0;
            int xIndex;
            int yIndex = (int) Math.floor((mapGeoBounds.getMaxY() - gridCoverageOrigin.y) /
                                                  tileSizeInWorld.y) + 1;

            double gridCoverageMaxX = gridCoverageOrigin.x;
            double gridCoverageMaxY = gridCoverageOrigin.y;

            List<SingleTilePreparationInfo> tiles = Lists.newArrayList();

            for (double geoY = gridCoverageOrigin.y; geoY < mapGeoBounds.getMaxY();
                 geoY += tileSizeInWorld.y) {
                yIndex--;
                imageHeight += tileSizeOnScreen.height;
                imageWidth = 0;
                xIndex = -1;

                gridCoverageMaxX = gridCoverageOrigin.x;
                gridCoverageMaxY += tileSizeInWorld.y;
                for (double geoX = gridCoverageOrigin.x; geoX < mapGeoBounds.getMaxX();
                     geoX += tileSizeInWorld.x) {
                    xIndex++;
                    imageWidth += tileSizeOnScreen.width;
                    gridCoverageMaxX += tileSizeInWorld.x;

                    ReferencedEnvelope tileBounds = new ReferencedEnvelope(
                            geoX, geoX + tileSizeInWorld.x, geoY, geoY + tileSizeInWorld.y,
                            mapProjection);

                    int row = (int) Math.round((tileCacheBounds.getMaxY() -
                            tileBounds.getMaxY()) * rowFactor);
                    int column = (int) Math.round((tileBounds.getMinX() -
                            tileCacheBounds.getMinX()) * columnFactor);

                    ClientHttpRequest tileRequest = this.tiledLayer.getTileRequest(this.httpRequestFactory,
                                                                                   commonUrl, tileBounds,
                                                                                   tileSizeOnScreen, column,
                                                                                   row);
                    if (isInTileCacheBounds(tileCacheBounds, tileBounds)) {
                        if (isTileVisible(tileBounds)) {
                            tileRequest = this.requestCache.register(tileRequest);
                            tiles.add(new SingleTilePreparationInfo(xIndex, yIndex, tileRequest));
                        }
                    } else {
                        LOGGER.debug("Tile out of bounds: {}", tileRequest);
                        tiles.add(new SingleTilePreparationInfo(xIndex, yIndex, null));
                    }
                }
            }

            return new TilePreparationInfo(tiles, imageWidth, imageHeight, gridCoverageOrigin,
                                           gridCoverageMaxX, gridCoverageMaxY, mapProjection);
        } catch (Exception e) {
            throw ExceptionUtils.getRuntimeException(e);
        }
    }

    private boolean isInTileCacheBounds(
            final ReferencedEnvelope tileCacheBounds,
            final ReferencedEnvelope tilesBounds) {
        final double boundsMinX = tilesBounds.getMinX();
        final double boundsMinY = tilesBounds.getMinY();
        return boundsMinX >= tileCacheBounds.getMinX() && boundsMinX <= tileCacheBounds.getMaxX()
                && boundsMinY >= tileCacheBounds.getMinY() && boundsMinY <= tileCacheBounds.getMaxY();
        // we don't use maxX and maxY since tilecache doesn't seems to care about those...
    }

    /**
     * When using a map rotation, there might be tiles that are outside the rotated map area. To avoid to load
     * these tiles, this method checks if a tile is really required to draw the map.
     */
    private boolean isTileVisible(final ReferencedEnvelope tileBounds) {
        if (FloatingPointUtil.equals(this.transformer.getRotation(), 0.0)) {
            return true;
        }

        final GeometryFactory gfac = new GeometryFactory();
        final Optional<Geometry> rotatedMapBounds = getRotatedMapBounds(gfac);

        if (rotatedMapBounds.isPresent()) {
            return rotatedMapBounds.get().intersects(gfac.toGeometry(tileBounds));
        } else {
            // in case of an error, we simply load the tile
            return true;
        }
    }

    private Optional<Geometry> getRotatedMapBounds(final GeometryFactory gfac) {
        if (this.cachedRotatedMapBounds != null) {
            return this.cachedRotatedMapBounds;
        }

        // get the bounds for the unrotated map area
        final ReferencedEnvelope mapBounds = this.transformer.getBounds().toReferencedEnvelope(
                new Rectangle(this.transformer.getMapSize()));

        // then rotate the geometry around its center
        final Coordinate center = mapBounds.centre();
        final AffineTransform affineTransform = AffineTransform.getRotateInstance(
                this.transformer.getRotation(), center.x, center.y);
        final MathTransform mathTransform = new AffineTransform2D(affineTransform);

        try {
            final Geometry rotatedBounds = JTS.transform(gfac.toGeometry(mapBounds), mathTransform);
            this.cachedRotatedMapBounds = Optional.of(rotatedBounds);
        } catch (TransformException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failed to rotate map bounds: {}", mapBounds.toString(), e);
            }
            this.cachedRotatedMapBounds = Optional.absent();
        }

        return this.cachedRotatedMapBounds;
    }
}
