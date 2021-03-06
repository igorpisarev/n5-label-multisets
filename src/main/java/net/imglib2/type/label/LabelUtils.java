package net.imglib2.type.label;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.cache.util.LoaderCacheAsCacheAdapter;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.label.LabelMultisetType.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LabelUtils
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	public static byte[] serializeLabelMultisetTypes(
			final Iterable< LabelMultisetType > lmts,
			final int numElements )
	{

		final int[] data = new int[ numElements ];
		final TLongArrayList argMax = new TLongArrayList();

		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( 32 );

		final LabelMultisetEntryList list = new LabelMultisetEntryList( listData, 0 );
		final LabelMultisetEntryList list2 = new LabelMultisetEntryList();
		final TIntArrayList listHashesAndOffsets = new TIntArrayList();
		final LabelMultisetEntry tentry = new LabelMultisetEntry( 0, 1 );
		int nextListOffset = 0;
		int o = 0;
		for ( final LabelMultisetType lmt : lmts )
		{
			list.createListAt( listData, nextListOffset );

			for ( final Entry< Label > entry : lmt.entrySet() )
			{
				final long id = entry.getElement().id();
				tentry.setId( id );
				tentry.setCount( entry.getCount() );
				list.add( tentry );
			}
			argMax.add( lmt.argMax() );

			boolean makeNewList = true;
			final int hash = list.hashCode();
			for ( int i = 0; i < listHashesAndOffsets.size(); i += 2 )
			{
				if ( hash == listHashesAndOffsets.get( i ) )
				{
					list2.referToDataAt( listData, listHashesAndOffsets.get( i + 1 ) );
					if ( list.equals( list2 ) )
					{
						makeNewList = false;
						data[ o++ ] = listHashesAndOffsets.get( i + 1 );
						break;
					}
				}
			}
			if ( makeNewList )
			{
				data[ o++ ] = nextListOffset;
				listHashesAndOffsets.add( hash );
				listHashesAndOffsets.add( nextListOffset );
				nextListOffset += list.getSizeInBytes();
			}
		}

		final byte[] bytes = new byte[ VolatileLabelMultisetArray.getRequiredNumberOfBytes( argMax.size(), data, nextListOffset ) ];

		final ByteBuffer bb = ByteBuffer.wrap( bytes );

		bb.putInt( argMax.size() );
		for ( final TLongIterator it = argMax.iterator(); it.hasNext(); )
		{
			bb.putLong( it.next() );
		}

		for ( final int d : data )
		{
			bb.putInt( d );
		}

		for ( int i = 0; i < nextListOffset; ++i )
		{
			bb.put( ByteUtils.getByte( listData.data, i ) );
		}

		return bytes;
	}

	public static LabelMultisetType getOutOfBounds()
	{
		return getOutOfBounds( 1 );
	}

	public static LabelMultisetType getOutOfBounds( final int count )
	{

		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( 32 );

		final LabelMultisetEntryList list = new LabelMultisetEntryList( listData, 0 );
		final LabelMultisetEntry entry = new LabelMultisetEntry( 0, 1 );

		list.createListAt( listData, 0 );
		entry.setId( Label.OUTSIDE );
		entry.setCount( count );
		list.add( entry );

		final int[] data = new int[] { 0 };

		final TLongHashSet containedLabels = new TLongHashSet();
		containedLabels.add( Label.OUTSIDE );

		return new LabelMultisetType( new VolatileLabelMultisetArray( data, listData, true, new long[] { Label.OUTSIDE } ) );
	}

	public static VolatileLabelMultisetArray fromBytes( final byte[] bytes, final int numElements )
	{
		final ByteBuffer bb = ByteBuffer.wrap( bytes );
		LOG.debug( "Creating VolatileLabelMultisetArray from {} bytes for {} elements", bytes.length, numElements );

		final int argMaxSize = bb.getInt();
		LOG.debug( "Data contains {} arg maxes", argMaxSize );
		final long[] argMax = new long[ argMaxSize ];
		for ( int i = 0; i < argMaxSize; ++i )
		{
			argMax[ i ] = bb.getLong();
		}

		final int[] data = new int[ numElements ];
		final int listDataSize = bytes.length - ( AbstractLabelMultisetLoader.listOffsetsSizeInBytes( data.length )
				+ AbstractLabelMultisetLoader.argMaxListSizeInBytes( argMax.length ) );
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( listDataSize );

		for ( int i = 0; i < data.length; ++i )
		{
			data[ i ] = bb.getInt();
		}

		for ( int i = 0; i < listDataSize; ++i )
		{
			ByteUtils.putByte( bb.get(), listData.data, i );
		}
		return new VolatileLabelMultisetArray( data, listData, true, argMax );
	}

	public static CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > openVolatile(
			final N5Reader n5,
			final String dataset ) throws IOException
	{
		final DatasetAttributes attrs = n5.getDatasetAttributes( dataset );
		return openVolatile( new N5CacheLoader( n5, dataset, N5CacheLoader.constantNullReplacement( Label.BACKGROUND ) ), attrs.getDimensions(), attrs.getBlockSize() );
	}

	public static CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > openVolatile(
			final CacheLoader< Long, Cell< VolatileLabelMultisetArray > > loader,
			final long[] dimensions,
			final int[] blockSize )
	{
		return openVolatile( loader, new CellGrid( dimensions, blockSize ) );
	}

	public static CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > openVolatile(
			final CacheLoader< Long, Cell< VolatileLabelMultisetArray > > loader,
			final CellGrid grid )
	{
		final SoftRefLoaderCache< Long, Cell< VolatileLabelMultisetArray > > cache = new SoftRefLoaderCache<>();
		final LoaderCacheAsCacheAdapter< Long, Cell< VolatileLabelMultisetArray > > wrappedCache = new LoaderCacheAsCacheAdapter<>( cache, loader );
		final CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > cachedImg = new CachedCellImg<>(
				grid,
				new LabelMultisetType().getEntitiesPerPixel(),
				wrappedCache,
				new VolatileLabelMultisetArray( 0, true, new long[] { Label.INVALID } ) );
		cachedImg.setLinkedType( new LabelMultisetType( cachedImg ) );
		return cachedImg;
	}
}
