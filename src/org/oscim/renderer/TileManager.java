/*
 * Copyright 2012 OpenScienceMap
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.oscim.renderer;

import static org.oscim.generator.JobTile.STATE_LOADING;
import static org.oscim.generator.JobTile.STATE_NEW_DATA;
import static org.oscim.generator.JobTile.STATE_NONE;
import static org.oscim.generator.JobTile.STATE_READY;

import java.util.ArrayList;
import java.util.Arrays;

import org.oscim.core.MapPosition;
import org.oscim.core.Tile;
import org.oscim.generator.JobTile;
import org.oscim.generator.TileDistanceSort;
import org.oscim.renderer.layer.TextItem;
import org.oscim.renderer.layer.VertexPool;
import org.oscim.view.MapView;
import org.oscim.view.MapViewPosition;

import android.util.Log;

/**
 * @author Hannes Janetzek
 * @TODO
 *       - this class should probably not be in 'renderer' -> tilemap?
 *       - make it general for reuse in tile-overlays
 */
public class TileManager {
	static final String TAG = TileManager.class.getSimpleName();

	private static final int MAX_TILES_IN_QUEUE = 40;
	private static final int CACHE_THRESHOLD = 30;

	private final MapView mMapView;

	private final MapPosition mMapPosition = new MapPosition();
	private final MapViewPosition mMapViewPosition;

	// all tiles
	private MapTile[] mTiles;
	// actual number of tiles in mTiles
	private int mTilesCount;
	// current end position in mTiles
	private int mTilesSize;
	// first free slot in mTiles
	//private static int mTilesFirst;

	// new jobs for MapWorkers
	private ArrayList<JobTile> mJobs;

	// tiles that have new data to upload, see passTile()
	private ArrayList<MapTile> mTilesLoaded;

	private boolean mInitialized;

	// private static MapPosition mCurPosition, mDrawPosition;
	private int mWidth = 0, mHeight = 0;

	private float[] mTileCoords = new float[8];

	private static int mUpdateCnt;
	static Object tilelock = new Object();
	private static TileSet mCurrentTiles;
	/* package */static TileSet mNewTiles;

	private final ScanBox mScanBox = new ScanBox() {

		@Override
		public void setVisible(int y, int x1, int x2) {
			MapTile[] tiles = mNewTiles.tiles;
			int cnt = mNewTiles.cnt;
			int max = tiles.length;
			int xmax = 1 << mZoom;

			for (int x = x1; x < x2; x++) {
				MapTile tile = null;

				if (cnt == max) {
					Log.d(TAG, "reached maximum for currentTiles " + max);
					break;
				}

				// NOTE to myself: do not modify x!
				int xx = x;

				if (x < 0 || x >= xmax) {
					// flip-around date line
					if (x < 0)
						xx = xmax + x;
					else
						xx = x - xmax;

					if (xx < 0 || xx >= xmax)
						continue;
				}

				// check if tile is already added
				for (int i = 0; i < cnt; i++)
					if (tiles[i].tileX == xx && tiles[i].tileY == y) {
						tile = tiles[i];
						break;
					}

				if (tile == null) {
					tile = addTile(xx, y, mZoom, 0);
					tiles[cnt++] = tile;
				}
			}
			mNewTiles.cnt = cnt;
		}
	};

	public void destroy() {
		// there might be some leaks in here
		// mRenderer = null;
		// ... free pools
	}

	public TileManager(MapView mapView) {
		Log.d(TAG, "init TileManager");
		mMapView = mapView;
		mMapViewPosition = mapView.getMapViewPosition();

		mJobs = new ArrayList<JobTile>();
		mTiles = new MapTile[GLRenderer.CACHE_TILES];
		mTilesLoaded = new ArrayList<MapTile>(30);

		mTilesSize = 0;
		mUpdateCnt = 0;
		mInitialized = false;
	}

	/**
	 * Update list of visible tiles and passes them to TileManager, when not
	 * available tiles are created and added to JobQueue (mapView.addJobs) for
	 * loading by TileGenerator class
	 * @param clear
	 *            whether to clear and reload all tiles
	 */
	public synchronized void updateMap(final boolean clear) {
		boolean changedPos = false;

		if (mMapView == null)
			return;

		if (clear || !mInitialized) {
			// make sure onDrawFrame is not running
			// - and labeling thread?
			GLRenderer.drawlock.lock();

			// clear all tiles references
			Log.d(TAG, "CLEAR " + mInitialized);

			if (clear) {
				// pass VBOs and VertexItems back to pools
				for (int i = 0; i < mTilesSize; i++)
					clearTile(mTiles[i]);
			} else {
				// mInitialized is set when surface changed 
				// and VBOs might be lost
				VertexPool.init();
			}

			QuadTree.init();

			Arrays.fill(mTiles, null);
			mTilesSize = 0;
			mTilesCount = 0;

			mTilesLoaded.clear();

			for (TileSet td : mTileSets) {
				Arrays.fill(td.tiles, null);
				td.cnt = 0;
			}

			// set up TileData arrays that are passed to gl-thread
			int num = Math.max(mWidth, mHeight);
			int size = Tile.TILE_SIZE >> 1;
			int numTiles = (num * num) / (size * size) * 4;
			mNewTiles = new TileSet(numTiles);
			mCurrentTiles = new TileSet(numTiles);

			// make sure mMapPosition will be updated
			mMapPosition.zoomLevel = -1;
			mInitialized = true;

			GLRenderer.drawlock.unlock();
		}

		MapPosition mapPosition = mMapPosition;
		float[] coords = mTileCoords;
		changedPos = mMapViewPosition.getMapPosition(mapPosition, coords);

		if (changedPos) {
			mMapView.render();
		} else {
			return;
		}

		// load some tiles more than currently visible
		// TODO limit how many more...
		float scale = mapPosition.scale * 0.5f;
		float px = (float) mapPosition.x;
		float py = (float) mapPosition.y;

		// TODO hint whether to prefetch parent / children
		int zdir = 0;

		for (int i = 0; i < 8; i += 2) {
			coords[i + 0] = (px + coords[i + 0] / scale) / Tile.TILE_SIZE;
			coords[i + 1] = (py + coords[i + 1] / scale) / Tile.TILE_SIZE;
		}

		boolean changed = updateVisibleList(mapPosition, zdir);

		if (changed) {
			mMapView.render();

			int remove = mTilesCount - GLRenderer.CACHE_TILES;
			if (remove > CACHE_THRESHOLD)
				limitCache(mapPosition, remove);

			limitLoadQueue();
		}
	}

	// need to keep track of TileSets to clear on reset...
	private static ArrayList<TileSet> mTileSets = new ArrayList<TileSet>(2);

	public static TileSet getActiveTiles(TileSet td) {
		if (mCurrentTiles == null)
			return td;

		if (td != null && td.serial == mUpdateCnt)
			return td;

		// dont flip new/currentTiles while copying
		synchronized (tilelock) {
			MapTile[] newTiles = mCurrentTiles.tiles;
			int cnt = mCurrentTiles.cnt;

			// lock tiles (and their proxies) to not be removed from cache
			for (int i = 0; i < cnt; i++)
				newTiles[i].lock();

			MapTile[] nextTiles;

			if (td == null) {
				td = new TileSet(newTiles.length);
				mTileSets.add(td);
			}

			nextTiles = td.tiles;

			// unlock previously active tiles
			for (int i = 0, n = td.cnt; i < n; i++)
				nextTiles[i].unlock();

			// copy newTiles to nextTiles
			System.arraycopy(newTiles, 0, nextTiles, 0, cnt);

			td.serial = mUpdateCnt;
			td.cnt = cnt;
		}

		return td;
	}

	// public void releaseTiles(TileSet tiles) {
	//
	// }

	/**
	 * set mNewTiles for the visible tiles and pass it to GLRenderer, add jobs
	 * for not yet loaded tiles
	 * @param mapPosition
	 *            the current MapPosition
	 * @param zdir
	 *            zoom direction
	 * @return true if new tiles were loaded
	 */
	private boolean updateVisibleList(MapPosition mapPosition, int zdir) {
		// clear JobQueue and set tiles to state == NONE.
		// one could also append new tiles and sort in JobQueue
		// but this has the nice side-effect that MapWorkers dont
		// start with old jobs while new jobs are calculated, which
		// should increase the chance that they are free when new 
		// jobs come in.
		mMapView.addJobs(null);

		mNewTiles.cnt = 0;
		mScanBox.scan(mTileCoords, mapPosition.zoomLevel);

		MapTile[] newTiles = mNewTiles.tiles;
		MapTile[] curTiles = mCurrentTiles.tiles;

		boolean changed = (mNewTiles.cnt != mCurrentTiles.cnt);

		Arrays.sort(mNewTiles.tiles, 0, mNewTiles.cnt, TileSet.coordComparator);

		if (!changed) {
			for (int i = 0, n = mNewTiles.cnt; i < n; i++) {
				if (newTiles[i] != curTiles[i]) {
					changed = true;
					break;
				}
			}
		}

		if (changed) {
			synchronized (tilelock) {
				for (int i = 0, n = mNewTiles.cnt; i < n; i++)
					newTiles[i].lock();

				for (int i = 0, n = mCurrentTiles.cnt; i < n; i++)
					curTiles[i].unlock();

				TileSet tmp = mCurrentTiles;
				mCurrentTiles = mNewTiles;
				mNewTiles = tmp;

				mUpdateCnt++;
			}
		}
		//Log.d(TAG, "tiles: " + mCurrentTiles.cnt + " added: " + mJobs.size());
		if (mJobs.size() > 0) {

			JobTile[] jobs = new JobTile[mJobs.size()];
			jobs = mJobs.toArray(jobs);
			updateTileDistances(jobs, jobs.length, mapPosition);

			// sets tiles to state == LOADING
			mMapView.addJobs(jobs);
			mJobs.clear();
			return true;
		}
		return false;
	}

	/**
	 * @param x
	 *            ...
	 * @param y
	 *            ...
	 * @param zoomLevel
	 *            ...
	 * @param zdir
	 *            ...
	 * @return ...
	 */

	/* package */MapTile addTile(int x, int y, byte zoomLevel, int zdir) {
		MapTile tile;

		tile = QuadTree.getTile(x, y, zoomLevel);

		if (tile == null) {
			tile = new MapTile(x, y, zoomLevel);
			QuadTree.add(tile);

			if (mTilesSize == mTiles.length) {
				Log.d(TAG, "repack: " + mTiles.length + " / " + mTilesCount);

				TileDistanceSort.sort(mTiles, 0, mTilesSize);

				if (mTilesCount == mTilesSize) {
					Log.d(TAG, "realloc tiles");
					MapTile[] tmp = new MapTile[mTiles.length + 20];
					System.arraycopy(mTiles, 0, tmp, 0, mTilesCount);
					mTiles = tmp;
				}
				mTilesSize = mTilesCount;
			}

			mTiles[mTilesSize++] = tile;
			mJobs.add(tile);
			mTilesCount++;

		} else if (!tile.isActive()) {
			mJobs.add(tile);
		}

		if (zoomLevel > 0) {
			// prefetch parent
			MapTile p = tile.rel.parent.tile;

			if (p == null) {
				p = new MapTile(x >> 1, y >> 1, (byte) (zoomLevel - 1));
				QuadTree.add(p);

				p.state = STATE_LOADING;
				mJobs.add(p);

			} else if (!p.isActive()) {
				//Log.d(TAG, "prefetch parent " + p);
				p.state = STATE_LOADING;
				mJobs.add(p);
			}
		}

		return tile;
	}

	private void clearTile(MapTile t) {
		if (t == null)
			return;

		if (t.layers != null) {
			t.layers.clear();
			t.layers = null;
		}

		TextItem.release(t.labels);

		if (t.vbo != null) {
			BufferObject.release(t.vbo);
			t.vbo = null;
		}

		QuadTree.remove(t);
		t.state = STATE_NONE;

		mTilesCount--;
	}

	private static void updateTileDistances(Object[] tiles, int size, MapPosition mapPosition) {
		int h = (Tile.TILE_SIZE >> 1);
		byte zoom = mapPosition.zoomLevel;
		long x = (long) mapPosition.x;
		long y = (long) mapPosition.y;
		long center = Tile.TILE_SIZE << (zoom - 1);
		int diff;
		long dx, dy;

		for (int i = 0; i < size; i++) {
			JobTile t = (JobTile) tiles[i];
			if (t == null)
				continue;

			diff = (t.zoomLevel - zoom);

			if (diff == 0) {
				dx = (t.pixelX + h) - x;
				dy = (t.pixelY + h) - y;
				dx %= center;
				dy %= center;
				t.distance = (dx * dx + dy * dy) * 0.5f;
			} else if (diff > 0) {
				// tile zoom level is child of current
				if (diff < 3) {
					dx = ((t.pixelX + h) >> diff) - x;
					dy = ((t.pixelY + h) >> diff) - y;
				}
				else {
					dx = ((t.pixelX + h) >> (diff >> 1)) - x;
					dy = ((t.pixelY + h) >> (diff >> 1)) - y;
				}
				dx %= center;
				dy %= center;
				t.distance = (dx * dx + dy * dy);

			} else {
				// tile zoom level is parent of current
				dx = ((t.pixelX + h) << -diff) - x;
				dy = ((t.pixelY + h) << -diff) - y;
				dx %= center;
				dy %= center;
				t.distance = (dx * dx + dy * dy) * (-diff * 0.7f);
			}
		}
	}

	private void limitCache(MapPosition mapPosition, int remove) {
		MapTile[] tiles = mTiles;
		int size = mTilesSize;

		// remove tiles that were never loaded
		for (int i = 0; i < size; i++) {
			MapTile t = tiles[i];
			if (t == null)
				continue;

			// make sure tile cannot be used by GL or MapWorker Thread
			if ((t.state != 0) || t.isLocked()) {
				continue;
			}
			clearTile(t);
			tiles[i] = null;
			remove--;
		}

		if (remove > 10) {
			updateTileDistances(tiles, size, mapPosition);

			// double start, end;
			// start = SystemClock.uptimeMillis();

			TileDistanceSort.sort(tiles, 0, size);

			//end = SystemClock.uptimeMillis();
			//Log.d(TAG, "sort took " + (end - start) +
			//	"limitCache: repacked: " + mTilesSize + " to: " + mTilesCount);

			// sorting also repacks the 'sparse' filled array
			// so end of mTiles is at mTilesCount now
			mTilesSize = size = mTilesCount;

			for (int i = 1; i < remove; i++) {
				MapTile t = tiles[size - i];
				if (t.isLocked()) {
					// dont remove tile used by GLRenderer, or somewhere else
					Log.d(TAG, "limitCache: tile still locked " + t + " " + t.distance);
					//mTiles.add(t);
				} else if (t.state == STATE_LOADING) {
					// NOTE: if we add tile back and set loading=false, on next
					// limitCache the tile will be removed. clearTile could
					// interfere with TileGenerator. so clear in passTile()
					// instead.
					// ... no, this does not work either: when set loading to
					// false tile could be added to load queue while still
					// processed in TileGenerator => need tile.cancel flag.
					// t.isLoading = false;
					//mTiles.add(t);
					Log.d(TAG, "limitCache: cancel loading " + t + " " + t.distance);
				} else {
					clearTile(t);
					tiles[size - i] = null;
				}
			}
		}
	}

	private void limitLoadQueue() {
		int size = mTilesLoaded.size();

		if (size < MAX_TILES_IN_QUEUE)
			return;

		synchronized (mTilesLoaded) {
			// remove tiles already uploaded to vbo
			for (int i = 0; i < size;) {
				MapTile t = mTilesLoaded.get(i);
				if (t.state == STATE_READY || t.state == STATE_NONE) {
					mTilesLoaded.remove(i);
					size--;
					continue;
				}
				i++;
			}

			if (size < MAX_TILES_IN_QUEUE)
				return;

			// clear loaded but not used tiles
			for (int i = 0, n = size - MAX_TILES_IN_QUEUE / 2; i < n; n--) {
				MapTile t = mTilesLoaded.get(i);

				if (t.isLocked()) {
					i++;
					continue;
				}

				mTilesLoaded.remove(i);

				// remove reference from mTiles
				MapTile[] tiles = mTiles;
				for (int j = 0, m = mTilesSize; j < m; j++) {
					if (t == tiles[j]) {
						mTiles[j] = null;
						break;
					}
				}

				clearTile(t);
			}
		}
	}

	/**
	 * called from MapWorker Thread when tile is loaded by TileGenerator
	 * @param jobTile
	 *            ...
	 * @return ...
	 */
	public synchronized boolean passTile(JobTile jobTile) {
		MapTile tile = (MapTile) jobTile;

		if (tile.state != STATE_LOADING) {
			// - should rather be STATE_FAILED
			// no one should be able to use this tile now, TileGenerator passed
			// it, GL-Thread does nothing until newdata is set.
			//Log.d(TAG, "passTile: failed loading " + tile);
			return true;
		}

		if (tile.vbo != null) {
			// BAD Things(tm) happend: tile is already loaded
			Log.d(TAG, "BUG: tile loaded before " + tile);
			return true;
		}

		tile.state = STATE_NEW_DATA;

		//if (tile.isVisible)
		mMapView.render();

		synchronized (mTilesLoaded) {
			//if (!mTilesLoaded.contains(tile))
			mTilesLoaded.add(tile);
		}

		return true;
	}

	public void onSizeChanged(int w, int h) {
		Log.d(TAG, "onSizeChanged" + w + " " + h);

		mWidth = w;
		mHeight = h;

		// size changed does not mean gl surface was recreated 
		// FIXME iirc this was the case before honeycomb
		//if (mWidth > 0 && mHeight > 0)
		//	mInitialized = true;
	}
}
