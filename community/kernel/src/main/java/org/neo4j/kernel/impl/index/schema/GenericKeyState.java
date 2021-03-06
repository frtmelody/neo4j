/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;

import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.impl.index.schema.GenericLayout.Type;
import org.neo4j.kernel.impl.store.TemporalValueWriterAdapter;
import org.neo4j.string.UTF8;
import org.neo4j.values.storable.BooleanValue;
import org.neo4j.values.storable.DateTimeValue;
import org.neo4j.values.storable.DateValue;
import org.neo4j.values.storable.DurationValue;
import org.neo4j.values.storable.LocalDateTimeValue;
import org.neo4j.values.storable.LocalTimeValue;
import org.neo4j.values.storable.NumberValue;
import org.neo4j.values.storable.PrimitiveArrayWriting;
import org.neo4j.values.storable.TimeValue;
import org.neo4j.values.storable.TimeZones;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueGroup;
import org.neo4j.values.storable.Values;

import static java.lang.Integer.min;
import static org.neo4j.io.pagecache.PageCache.PAGE_SIZE;
import static org.neo4j.kernel.impl.index.schema.DurationIndexKey.AVG_DAY_SECONDS;
import static org.neo4j.kernel.impl.index.schema.DurationIndexKey.AVG_MONTH_SECONDS;
import static org.neo4j.kernel.impl.index.schema.GenericLayout.HIGHEST_TYPE_BY_VALUE_GROUP;
import static org.neo4j.kernel.impl.index.schema.GenericLayout.LOWEST_TYPE_BY_VALUE_GROUP;
import static org.neo4j.kernel.impl.index.schema.NativeIndexKey.Inclusion.HIGH;
import static org.neo4j.kernel.impl.index.schema.NativeIndexKey.Inclusion.LOW;
import static org.neo4j.kernel.impl.index.schema.NativeIndexKey.Inclusion.NEUTRAL;
import static org.neo4j.kernel.impl.index.schema.StringIndexKey.lexicographicalUnsignedByteArrayCompare;
import static org.neo4j.kernel.impl.index.schema.ZonedDateTimeLayout.ZONE_ID_FLAG;
import static org.neo4j.kernel.impl.index.schema.ZonedDateTimeLayout.ZONE_ID_MASK;
import static org.neo4j.kernel.impl.index.schema.ZonedDateTimeLayout.asZoneId;
import static org.neo4j.kernel.impl.index.schema.ZonedDateTimeLayout.asZoneOffset;
import static org.neo4j.kernel.impl.index.schema.ZonedDateTimeLayout.isZoneId;
import static org.neo4j.values.storable.Values.NO_VALUE;

class GenericKeyState extends TemporalValueWriterAdapter<RuntimeException>
{
    // TODO copy-pasted from individual keys
    // TODO also put this in Type enum
    private static final int SIZE_ZONED_DATE_TIME = Long.BYTES +    /* epochSecond */
                                                    Integer.BYTES + /* nanoOfSecond */
                                                    Integer.BYTES;  /* timeZone */
    private static final int SIZE_LOCAL_DATE_TIME = Long.BYTES +    /* epochSecond */
                                                    Integer.BYTES;  /* nanoOfSecond */
    private static final int SIZE_DATE =            Long.BYTES;     /* epochDay */
    private static final int SIZE_ZONED_TIME =      Long.BYTES +    /* nanosOfDayUTC */
                                                    Integer.BYTES;  /* zoneOffsetSeconds */
    private static final int SIZE_LOCAL_TIME =      Long.BYTES;     /* nanoOfDay */
    private static final int SIZE_DURATION =        Long.BYTES +    /* totalAvgSeconds */
                                                    Integer.BYTES + /* nanosOfSecond */
                                                    Long.BYTES +    /* months */
                                                    Long.BYTES;     /* days */
    private static final int SIZE_BOOLEAN =         Byte.BYTES;     /* byte for this boolean value */
    private static final int SIZE_NUMBER_TYPE =     Byte.BYTES;     /* type of value */
    private static final int SIZE_NUMBER =          Long.BYTES;     /* raw value bits */
    private static final int SIZE_ARRAY_LENGTH =    Integer.BYTES;
    private static final int BIGGEST_REASONABLE_ARRAY_LENGTH = PAGE_SIZE / 2 / Integer.SIZE;

    private static final long TRUE = 1;
    private static final long FALSE = 0;
    private static final int TYPE_ID_SIZE = Byte.BYTES;

    Type type;
    NativeIndexKey.Inclusion inclusion;
    private boolean isArray;
    private int arrayLength;
    private int currentArrayOffset;

    // zoned date time:       long0 (epochSecondUTC), long1 (nanoOfSecond), long2 (zoneId), long3 (zoneOffsetSeconds)
    // local date time:       long0 (nanoOfSecond), long1 (epochSecond)
    // date:                  long0 (epochDay)
    // zoned time:            long0 (nanosOfDayUTC), long1 (zoneOffsetSeconds)
    // local time:            long0 (nanoOfDay)
    // duration:              long0 (totalAvgSeconds), long1 (nanosOfSecond), long2 (months), long3 (days)
    // text:                  long0 (length), long1 (bytesDereferenced), long2 (ignoreLength), long3 (isHighest), byteArray
    // boolean:               long0
    // number:                long0 (value), long1 (number type)
    // TODO spatial

    // for non-array values
    private long long0;
    private long long1;
    private long long2;
    private long long3;
    private byte[] byteArray;

    // for array values
    private long[] long0Array;
    private long[] long1Array;
    private long[] long2Array;
    private long[] long3Array;
    private byte[][] byteArrayArray;

    /* <initializers> */
    void clear()
    {
        type = null;
        long0 = 0;
        long1 = 0;
        long2 = 0;
        long3 = 0;
        inclusion = NEUTRAL;
        isArray = false;
        arrayLength = 0;
        currentArrayOffset = 0;
    }

    void initializeToDummyValue()
    {
        clear();
        writeInteger( 0 );
        inclusion = NEUTRAL;
    }

    // todo is this simple lowest approach viable? Probably not when including arrays
    void initValueAsLowest( ValueGroup valueGroup )
    {
        type = valueGroup == ValueGroup.UNKNOWN ? LOWEST_TYPE_BY_VALUE_GROUP : GenericLayout.TYPE_BY_GROUP[valueGroup.ordinal()];
        long0 = Long.MIN_VALUE;
        long1 = Long.MIN_VALUE;
        long2 = Long.MIN_VALUE;
        long3 = Long.MIN_VALUE;
        byteArray = null;
        if ( type == Type.TEXT )
        {
            long3 = FALSE;
        }
        inclusion = LOW;
    }

    // todo is this simple highest approach viable? Probably not when including arrays
    void initValueAsHighest( ValueGroup valueGroup )
    {
        type = valueGroup == ValueGroup.UNKNOWN ? HIGHEST_TYPE_BY_VALUE_GROUP : GenericLayout.TYPE_BY_GROUP[valueGroup.ordinal()];
        long0 = Long.MAX_VALUE;
        long1 = Long.MAX_VALUE;
        long2 = Long.MAX_VALUE;
        long3 = Long.MAX_VALUE;
        byteArray = null;
        if ( type == Type.TEXT )
        {
            long3 = TRUE;
        }
        inclusion = HIGH;
    }

    void initAsPrefixLow( String prefix )
    {
        writeString( prefix );
        long2 = FALSE;
        inclusion = LOW;
        // Don't set ignoreLength = true here since the "low" a.k.a. left side of the range should care about length.
        // This will make the prefix lower than those that matches the prefix (their length is >= that of the prefix)
    }

    void initAsPrefixHigh( String prefix )
    {
        writeString( prefix );
        long2 = TRUE; // ignoreLength
        inclusion = HIGH;
    }
    /* </initializers> */

    /* <copyFrom> */
    void copyFrom( GenericKeyState key )
    {
        this.type = key.type;
        this.inclusion = key.inclusion;
        this.isArray = key.isArray;
        if ( !key.isArray )
        {
            this.long0 = key.long0;
            this.long1 = key.long1;
            this.long2 = key.long2;
            this.long3 = key.long3;
            this.copyByteArrayFromIfExists( key, (int) key.long0 );
        }
        else
        {
            this.arrayLength = key.arrayLength;
            this.currentArrayOffset = key.currentArrayOffset;
            switch ( key.type )
            {
            case ZONED_DATE_TIME_ARRAY:
                copyZonedDateTimeArrayFrom( key );
                break;
            case LOCAL_DATE_TIME_ARRAY:
                copyLocalDateTimeArrayFrom( key );
                break;
            case DATE_ARRAY:
                copyDateArrayFrom( key );
                break;
            case ZONED_TIME_ARRAY:
                copyZonedTimeArrayFrom( key );
                break;
            case LOCAL_TIME_ARRAY:
                copyLocalTimeArrayFrom( key );
                break;
            case DURATION_ARRAY:
                copyDurationArrayFrom( key );
                break;
            case TEXT_ARRAY:
                copyTextArrayFrom( key );
                break;
            case BOOLEAN_ARRAY:
                copyBooleanArrayFrom( key );
                break;
            case NUMBER_ARRAY:
                copyNumberArrayFrom( key );
                this.long1 = key.long1;
                break;
            default:
                throw new IllegalStateException( "Expected an array type but was " + type );
            }
        }
    }

    private void copyByteArrayFromIfExists( GenericKeyState key, int targetLength )
    {
        if ( key.type == Type.TEXT )
        {
            setBytesLength( targetLength );
            System.arraycopy( key.byteArray, 0, byteArray, 0, targetLength );
        }
        else
        {
            byteArray = null;
        }
    }
    /* </copyFrom> */

    static Value assertCorrectType( Value value )
    {
        if ( Values.isGeometryValue( value ) )
        {
            throw new IllegalArgumentException( "Unsupported value " + value );
        }
        return value;
    }

    void writeValue( Value value, NativeIndexKey.Inclusion inclusion )
    {
        value.writeTo( this );
        this.inclusion = inclusion;
    }

    int compareValueTo( GenericKeyState other )
    {
        int typeComparison = GenericLayout.TYPE_COMPARATOR.compare( type, other.type );
        if ( typeComparison != 0 )
        {
            return typeComparison;
        }

        int valueComparison = internalCompareValueTo( other );
        if ( valueComparison != 0 )
        {
            return valueComparison;
        }

        return inclusion.compareTo( other.inclusion );
    }

    /* <size> */
    int size()
    {
        return valueSize() + TYPE_ID_SIZE;
    }

    private int valueSize()
    {
        switch ( type )
        {
        case ZONED_DATE_TIME:
            return SIZE_ZONED_DATE_TIME;
        case LOCAL_DATE_TIME:
            return SIZE_LOCAL_DATE_TIME;
        case DATE:
            return SIZE_DATE;
        case ZONED_TIME:
            return SIZE_ZONED_TIME;
        case LOCAL_TIME:
            return SIZE_LOCAL_TIME;
        case DURATION:
            return SIZE_DURATION;
        case TEXT:
            return Short.BYTES +    /* short field with bytesLength value */
                    (int) long0;    /* bytesLength */
        case BOOLEAN:
            return SIZE_BOOLEAN;
        case NUMBER:
            return SIZE_NUMBER + SIZE_NUMBER_TYPE;
        case ZONED_DATE_TIME_ARRAY:
            return arrayKeySize( SIZE_ZONED_DATE_TIME );
        case LOCAL_DATE_TIME_ARRAY:
            return arrayKeySize( SIZE_LOCAL_DATE_TIME );
        case DATE_ARRAY:
            return arrayKeySize( SIZE_DATE );
        case ZONED_TIME_ARRAY:
            return arrayKeySize( SIZE_ZONED_TIME );
        case LOCAL_TIME_ARRAY:
            return arrayKeySize( SIZE_LOCAL_TIME );
        case DURATION_ARRAY:
            return arrayKeySize( SIZE_DURATION );
        case TEXT_ARRAY:
            int stringArraySize = 0;
            for ( int i = 0; i < arrayLength; i++ )
            {
                stringArraySize += Short.BYTES + /* short field with bytesLength value */
                        (int) long0Array[i];    /* bytesLength */
            }
            return SIZE_ARRAY_LENGTH + stringArraySize;
        case BOOLEAN_ARRAY:
            return arrayKeySize( SIZE_BOOLEAN );
        case NUMBER_ARRAY:
            return arrayKeySize( SIZE_NUMBER ) + SIZE_NUMBER_TYPE;
        default:
            throw new IllegalArgumentException( "Unknown type " + type );
        }
    }

    private int arrayKeySize( int elementSize )
    {
        return SIZE_ARRAY_LENGTH + arrayLength * elementSize;
    }
    /* </size> */

    /* <asValue> */
    Value asValue()
    {
        switch ( type )
        {
        case ZONED_DATE_TIME:
            return zonedDateTimeAsValue( long0, long1, long2, long3 );
        case LOCAL_DATE_TIME:
            return localDateTimeAsValue( long0, long1 );
        case DATE:
            return dateAsValue( long0 );
        case ZONED_TIME:
            return zonedTimeAsValue( long0, long1 );
        case LOCAL_TIME:
            return localTimeAsValue( long0 );
        case DURATION:
            return durationAsValue( long0, long1, long2, long3 );
        case TEXT:
            long1 = TRUE; // bytes dereferenced
            return textAsValue( byteArray, long0 );
        case BOOLEAN:
            return booleanAsValue( long0 );
        case NUMBER:
            return numberAsValue( long0, long1 );
        case ZONED_DATE_TIME_ARRAY:
            return Values.of( populateValueArray( new ZonedDateTime[arrayLength],
                    i -> zonedDateTimeAsValueRaw( long0Array[i], long1Array[i], long2Array[i], long3Array[i] ) ) );
        case LOCAL_DATE_TIME_ARRAY:
            return Values.of( populateValueArray( new LocalDateTime[arrayLength], i -> localDateTimeAsValueRaw( long0Array[i], long1Array[i] ) ) );
        case DATE_ARRAY:
            return Values.of( populateValueArray( new LocalDate[arrayLength], i -> dateAsValueRaw( long0Array[i] ) ) );
        case ZONED_TIME_ARRAY:
            return Values.of( populateValueArray( new OffsetTime[arrayLength], i -> zonedTimeAsValueRaw( long0Array[i], long1Array[i] ) ) );
        case LOCAL_TIME_ARRAY:
            return Values.of( populateValueArray( new LocalTime[arrayLength], i -> localTimeAsValueRaw( long0Array[i] ) ) );
        case DURATION_ARRAY:
            return Values.of( populateValueArray( new DurationValue[arrayLength],
                    i -> durationAsValue( long0Array[i], long1Array[i], long2Array[i], long3Array[i] ) ) );
        case TEXT_ARRAY:
            // no need to set bytes dereferenced because byte[][] owned by this class will deserialized into String objects.
            return Values.of( populateValueArray( new String[arrayLength], i -> textAsValueRaw( byteArrayArray[i], long0Array[i] ) ) );
        case BOOLEAN_ARRAY:
            return booleanArrayAsValue();
        case NUMBER_ARRAY:
            return numberArrayAsValue();
        default:
            throw new IllegalArgumentException( "Unknown type " + type );
        }
    }

    private Value numberArrayAsValue()
    {
        byte numberType = (byte) this.long1;
        switch ( numberType )
        {
        case RawBits.BYTE:
            byte[] byteArray = new byte[arrayLength];
            for ( int i = 0; i < arrayLength; i++ )
            {
                byteArray[i] = (byte) long0Array[i];
            }
            return Values.byteArray( byteArray );
        case RawBits.SHORT:
            short[] shortArray = new short[arrayLength];
            for ( int i = 0; i < arrayLength; i++ )
            {
                shortArray[i] = (short) long0Array[i];
            }
            return Values.shortArray( shortArray );
        case RawBits.INT:
            int[] intArray = new int[arrayLength];
            for ( int i = 0; i < arrayLength; i++ )
            {
                intArray[i] = (int) long0Array[i];
            }
            return Values.intArray( intArray );
        case RawBits.LONG:
            return Values.longArray( Arrays.copyOf( long0Array, arrayLength ) );
        case RawBits.FLOAT:
            float[] floatArray = new float[arrayLength];
            for ( int i = 0; i < arrayLength; i++ )
            {
                floatArray[i] = Float.intBitsToFloat( (int) long0Array[i] );
            }
            return Values.floatArray( floatArray );
        case RawBits.DOUBLE:
            double[] doubleArray = new double[arrayLength];
            for ( int i = 0; i < arrayLength; i++ )
            {
                doubleArray[i] = Double.longBitsToDouble( long0Array[i] );
            }
            return Values.doubleArray( doubleArray );
        default:
            throw new IllegalArgumentException( "Unknown number type " + numberType );
        }
    }

    private Value booleanArrayAsValue()
    {
        boolean[] array = new boolean[arrayLength];
        for ( int i = 0; i < arrayLength; i++ )
        {
            array[i] = booleanAsValueRaw( long0Array[i] );
        }
        return Values.of( array );
    }

    private <T> T[] populateValueArray( T[] array, ArrayElementValueFactory<T> valueFactory )
    {
        for ( int i = 0; i < arrayLength; i++ )
        {
            array[i] = valueFactory.from( i );
        }
        return array;
    }
    /* </asValue> */

    /* <compare> */
    private int internalCompareValueTo( GenericKeyState that )
    {
        switch ( type )
        {
        case ZONED_DATE_TIME:
            return compareZonedDateTime(
                    this.long0, this.long1, this.long2, this.long3,
                    that.long0, that.long1, that.long2, that.long3 );
        case LOCAL_DATE_TIME:
            return compareLocalDateTime(
                    this.long0, this.long1,
                    that.long0, that.long1 );
        case DATE:
            return compareDate(
                    this.long0,
                    that.long0 );
        case ZONED_TIME:
            return compareZonedTime(
                    this.long0, this.long1,
                    that.long0, that.long1 );
        case LOCAL_TIME:
            return compareLocalTime(
                    this.long0,
                    that.long0 );
        case DURATION:
            return compareDuration(
                    this.long0, this.long1, this.long2, this.long3,
                    that.long0, that.long1, that.long2, that.long3 );
        case TEXT:
            return compareText(
                    this.byteArray, this.long0, this.long2, this.long3,
                    that.byteArray, that.long0, that.long2, that.long3 );
        case BOOLEAN:
            return compareBoolean(
                    this.long0,
                    that.long0 );
        case NUMBER:
            return compareNumber(
                    this.long0, this.long1,
                    that.long0, that.long1 );
        case ZONED_DATE_TIME_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareZonedDateTime(
                    o1.long0Array[i], o1.long1Array[i], o1.long2Array[i], o1.long3Array[i],
                    o2.long0Array[i], o2.long1Array[i], o2.long2Array[i], o2.long3Array[i] ) );
        case LOCAL_DATE_TIME_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareLocalDateTime(
                    o1.long0Array[i], o1.long1Array[i],
                    o2.long0Array[i], o2.long1Array[i] ) );
        case DATE_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareDate(
                    o1.long0Array[i],
                    o2.long0Array[i] ) );
        case ZONED_TIME_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareZonedTime(
                    o1.long0Array[i], o1.long1Array[i],
                    o2.long0Array[i], o2.long1Array[i] ) );
        case LOCAL_TIME_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareLocalTime(
                    o1.long0Array[i],
                    o2.long0Array[i] ) );
        case DURATION_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareDuration(
                    o1.long0Array[i], o1.long1Array[i], o1.long2Array[i], o1.long3Array[i],
                    o2.long0Array[i], o2.long1Array[i], o2.long2Array[i], o2.long3Array[i] ) );
        case TEXT_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareText(
                    o1.byteArrayArray[i], o1.long0Array[i], o1.long2, o1.long3,
                    o2.byteArrayArray[i], o2.long0Array[i], o2.long2, o2.long3 ) );
        case BOOLEAN_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareBoolean(
                    o1.long0Array[i],
                    o2.long0Array[i] ) );
        case NUMBER_ARRAY:
            return compareArrays( that, ( o1, o2, i ) -> compareNumber(
                    o1.long0Array[i], o1.long1,
                    o2.long0Array[i], o2.long1 ) );
        default:
            throw new IllegalArgumentException( "Unknown type " + type );
        }
    }

    private int compareArrays( GenericKeyState that, ArrayElementComparator comparator )
    {
        int i = 0;
        int x = 0;
        int length = min( this.arrayLength, that.arrayLength );

        for ( ; x == 0 && i < length; i++ )
        {
            x = comparator.compare( this, that, i );
        }

        return x == 0 ? this.arrayLength - that.arrayLength : x;
    }

    private static int compareNumber(
            long this_long0, long this_long1,
            long that_long0, long that_long1 )
    {
        return RawBits.compare( this_long0, (byte) this_long1, that_long0, (byte) that_long1 );
    }

    private static int compareBoolean(
            long this_long0,
            long that_long0 )
    {
        return Long.compare( this_long0, that_long0 );
    }

    private static int compareText(
            byte[] this_byteArray, long this_long0, long this_long2, long this_long3,
            byte[] that_byteArray, long that_long0, long that_long2, long that_long3 )
    {
        if ( this_byteArray != that_byteArray )
        {
            if ( this_byteArray == null )
            {
                return isHighestText( this_long3 ) ? 1 : -1;
            }
            if ( that_byteArray == null )
            {
                return isHighestText( that_long3 ) ? -1 : 1;
            }
        }
        else
        {
            return 0;
        }

        return lexicographicalUnsignedByteArrayCompare( this_byteArray, (int) this_long0, that_byteArray, (int) that_long0,
                booleanOf( this_long2 ) | booleanOf( that_long2 ) );
    }

    private static int compareZonedDateTime(
            long this_long0, long this_long1, long this_long2, long this_long3,
            long that_long0, long that_long1, long that_long2, long that_long3 )
    {
        int compare = Long.compare( this_long0, that_long0 );
        if ( compare == 0 )
        {
            compare = Integer.compare( (int) this_long1, (int) that_long1 );
            if ( compare == 0 &&
                    // We need to check validity upfront without throwing exceptions, because the PageCursor might give garbage bytes
                    TimeZones.validZoneOffset( (int) this_long3 ) &&
                    TimeZones.validZoneOffset( (int) that_long3 ) )
            {
                // In the rare case of comparing the same instant in different time zones, we settle for
                // mapping to values and comparing using the general values comparator.
                compare = Values.COMPARATOR.compare(
                        zonedDateTimeAsValue( this_long0, this_long1, this_long2, this_long3 ),
                        zonedDateTimeAsValue( that_long0, that_long1, that_long2, that_long3 ) );
            }
        }
        return compare;
    }

    private static int compareLocalDateTime(
            long this_long0, long this_long1,
            long that_long0, long that_long1 )
    {
        int compare = Long.compare( this_long1, that_long1 );
        if ( compare == 0 )
        {
            compare = Integer.compare( (int) this_long0, (int) that_long0 );
        }
        return compare;
    }

    private static int compareDate(
            long this_long0,
            long that_long0 )
    {
        return Long.compare( this_long0, that_long0 );
    }

    private static int compareZonedTime(
            long this_long0, long this_long1,
            long that_long0, long that_long1 )
    {
        int compare = Long.compare( this_long0, that_long0 );
        if ( compare == 0 )
        {
            compare = Integer.compare( (int) this_long1, (int) that_long1 );
        }
        return compare;
    }

    private static int compareLocalTime(
            long this_long0,
            long that_long0 )
    {
        return Long.compare( this_long0, that_long0 );
    }

    private static int compareDuration(
            long this_long0, long this_long1, long this_long2, long this_long3,
            long that_long0, long that_long1, long that_long2, long that_long3 )
    {
        int comparison = Long.compare( this_long0, that_long0 );
        if ( comparison == 0 )
        {
            comparison = Integer.compare( (int) this_long1, (int) that_long1 );
            if ( comparison == 0 )
            {
                comparison = Long.compare( this_long2, that_long2 );
                if ( comparison == 0 )
                {
                    comparison = Long.compare( this_long3, that_long3 );
                }
            }
        }
        return comparison;
    }
    /* </compare> */

    /* <put> */
    void put( PageCursor cursor )
    {
        cursor.putByte( type.typeId );
        switch ( type )
        {
        case ZONED_DATE_TIME:
            putZonedDateTime( cursor, long0, long1, long2, long3 );
            break;
        case LOCAL_DATE_TIME:
            putLocalDateTime( cursor, long0, long1 );
            break;
        case DATE:
            putDate( cursor, long0 );
            break;
        case ZONED_TIME:
            putZonedTime( cursor, long0, long1 );
            break;
        case LOCAL_TIME:
            putLocalTime( cursor, long0 );
            break;
        case DURATION:
            putDuration( cursor, long0, long1, long2, long3 );
            break;
        case TEXT:
            putText( cursor, byteArray, long0 );
            break;
        case BOOLEAN:
            putBoolean( cursor, long0 );
            break;
        case NUMBER:
            putNumber( cursor, long0, long1 );
            break;
        case ZONED_DATE_TIME_ARRAY:
            putArray( cursor, ( c, i ) -> putZonedDateTime( c, long0Array[i], long1Array[i], long2Array[i], long3Array[i] ) );
            break;
        case LOCAL_DATE_TIME_ARRAY:
            putArray( cursor, ( c, i ) -> putLocalDateTime( c, long0Array[i], long1Array[i] ) );
            break;
        case DATE_ARRAY:
            putArray( cursor, ( c, i ) -> putDate( c, long0Array[i] ) );
            break;
        case ZONED_TIME_ARRAY:
            putArray( cursor, ( c, i ) -> putZonedTime( c, long0Array[i], long1Array[i] ) );
            break;
        case LOCAL_TIME_ARRAY:
            putArray( cursor, ( c, i ) -> putLocalTime( c, long0Array[i] ) );
            break;
        case DURATION_ARRAY:
            putArray( cursor, ( c, i ) -> putDuration( c, long0Array[i], long1Array[i], long2Array[i], long3Array[i] ) );
            break;
        case TEXT_ARRAY:
            putArray( cursor, ( c, i ) -> putText( c, byteArrayArray[i], long0Array[i] ) );
            break;
        case BOOLEAN_ARRAY:
            putArray( cursor, ( c, i ) -> putBoolean( c, long0Array[i] ) );
            break;
        case NUMBER_ARRAY:
            cursor.putByte( (byte) long1 );
            putArray( cursor, ( c, i ) -> c.putLong( long0Array[i] ) );
            break;
        default:
            throw new IllegalArgumentException( "Unknown type " + type );
        }
    }

    interface ArrayElementWriter
    {
        void write( PageCursor cursor, int i );
    }

    private void putArray( PageCursor cursor, ArrayElementWriter writer )
    {
        cursor.putInt( arrayLength );
        for ( int i = 0; i < arrayLength; i++ )
        {
            writer.write( cursor, i );
        }
    }

    private static void putNumber( PageCursor cursor, long long0, long long1 )
    {
        cursor.putByte( (byte) long1 );
        cursor.putLong( long0 );
    }

    private static void putBoolean( PageCursor cursor, long long0 )
    {
        cursor.putByte( (byte) long0 );
    }

    private static void putText( PageCursor cursor, byte[] byteArray, long long0 )
    {
        short length = (short) long0;
        cursor.putShort( length );
        cursor.putBytes( byteArray, 0, length );
    }

    private static void putDuration( PageCursor cursor, long long0, long long1, long long2, long long3 )
    {
        cursor.putLong( long0 );
        cursor.putInt( (int) long1 );
        cursor.putLong( long2 );
        cursor.putLong( long3 );
    }

    private static void putLocalTime( PageCursor cursor, long long0 )
    {
        cursor.putLong( long0 );
    }

    private static void putZonedTime( PageCursor cursor, long long0, long long1 )
    {
        cursor.putLong( long0 );
        cursor.putInt( (int) long1 );
    }

    private static void putDate( PageCursor cursor, long long0 )
    {
        cursor.putLong( long0 );
    }

    private static void putLocalDateTime( PageCursor cursor, long long0, long long1 )
    {
        cursor.putLong( long1 );
        cursor.putInt( (int) long0 );
    }

    private static void putZonedDateTime( PageCursor cursor, long long0, long long1, long long2, long long3 )
    {
        cursor.putLong( long0 );
        cursor.putInt( (int) long1 );
        if ( long2 >= 0 )
        {
            cursor.putInt( (int) long2 | ZONE_ID_FLAG );
        }
        else
        {
            cursor.putInt( (int) long3 & ZONE_ID_MASK );
        }
    }
    /* </put> */

    /* <read> */
    boolean read( PageCursor cursor, int size )
    {
        if ( size <= TYPE_ID_SIZE )
        {
            return false;
        }

        byte typeId = cursor.getByte();
        if ( typeId < 0 || typeId >= GenericLayout.TYPES.length )
        {
            return false;
        }

        size -= TYPE_ID_SIZE;
        type = GenericLayout.TYPE_BY_ID[typeId];
        inclusion = NEUTRAL;
        switch ( type )
        {
        case ZONED_DATE_TIME:
            return readZonedDateTime( cursor );
        case LOCAL_DATE_TIME:
            return readLocalDateTime( cursor );
        case DATE:
            return readDate( cursor );
        case ZONED_TIME:
            return readZonedTime( cursor );
        case LOCAL_TIME:
            return readLocalTime( cursor );
        case DURATION:
            return readDuration( cursor );
        case TEXT:
            return readText( cursor, size );
        case BOOLEAN:
            return readBoolean( cursor );
        case NUMBER:
            return readNumber( cursor );
        case ZONED_DATE_TIME_ARRAY:
            return readArray( cursor, ArrayType.ZONED_DATE_TIME, this::readZonedDateTime );
        case LOCAL_DATE_TIME_ARRAY:
            return readArray( cursor, ArrayType.LOCAL_DATE_TIME, this::readLocalDateTime );
        case DATE_ARRAY:
            return readArray( cursor, ArrayType.DATE, this::readDate );
        case ZONED_TIME_ARRAY:
            return readArray( cursor, ArrayType.ZONED_TIME, this::readZonedTime );
        case LOCAL_TIME_ARRAY:
            return readArray( cursor, ArrayType.LOCAL_TIME, this::readLocalTime );
        case DURATION_ARRAY:
            return readArray( cursor, ArrayType.DURATION, this::readDuration );
        case TEXT_ARRAY:
            return readTextArray( cursor, size );
        case BOOLEAN_ARRAY:
            return readArray( cursor, ArrayType.BOOLEAN, this::readBoolean );
        case NUMBER_ARRAY:
            return readNumberArray( cursor );
        default:
            throw new IllegalArgumentException( "Unknown type " + type );
        }
    }

    private boolean readArray( PageCursor cursor, ArrayType type, ArrayElementReader reader )
    {
        if ( !setArrayLengthWhenReading( cursor ) )
        {
            return false;
        }
        beginArray( arrayLength, type );
        for ( int i = 0; i < arrayLength; i++ )
        {
            if ( !reader.readFrom( cursor ) )
            {
                return false;
            }
        }
        endArray();
        return true;
    }

    private boolean readNumberArray( PageCursor cursor )
    {
        long1 = cursor.getByte(); // number type, like: byte, int, short a.s.o.
        if ( !setArrayLengthWhenReading( cursor ) )
        {
            return false;
        }
        initializeNumberArray( arrayLength );
        ArrayType numberType = numberArrayTypeOf( (byte) long1 );
        if ( numberType == null )
        {
            return false;
        }

        beginArray( arrayLength, numberType );
        for ( int i = 0; i < arrayLength; i++ )
        {
            long0Array[i] = cursor.getLong();
        }
        endArray();
        return true;
    }

    private static ArrayType numberArrayTypeOf( byte numberType )
    {
        switch ( numberType )
        {
        case RawBits.BYTE:
            return ArrayType.BYTE;
        case RawBits.SHORT:
            return ArrayType.SHORT;
        case RawBits.INT:
            return ArrayType.INT;
        case RawBits.LONG:
            return ArrayType.LONG;
        case RawBits.FLOAT:
            return ArrayType.FLOAT;
        case RawBits.DOUBLE:
            return ArrayType.DOUBLE;
        default:
            // bad read, hopefully
            return null;
        }
    }

    private boolean readTextArray( PageCursor cursor, int maxSize )
    {
        if ( !setArrayLengthWhenReading( cursor ) )
        {
            return false;
        }
        beginArray( arrayLength, ArrayType.STRING );
        for ( int i = 0; i < arrayLength; i++ )
        {
            short bytesLength = cursor.getShort();
            if ( bytesLength <= 0 || bytesLength > maxSize )
            {
                return false;
            }

            byteArrayArray[i] = ensureBigEnough( byteArrayArray[i], bytesLength );
            long0Array[i] = bytesLength;
            cursor.getBytes( byteArrayArray[i], 0, bytesLength );
        }
        endArray();
        return true;
    }

    private boolean setArrayLengthWhenReading( PageCursor cursor )
    {
        arrayLength = cursor.getInt();
        return arrayLength >= 0 && arrayLength < BIGGEST_REASONABLE_ARRAY_LENGTH;
    }

    private boolean readNumber( PageCursor cursor )
    {
        long1 = cursor.getByte();
        long0 = cursor.getLong();
        return true;
    }

    private boolean readBoolean( PageCursor cursor )
    {
        writeBoolean( cursor.getByte() == TRUE );
        return true;
    }

    private boolean readText( PageCursor cursor, int maxSize )
    {
        // For performance reasons cannot be redirected to writeString, due to byte[] reuse
        short bytesLength = cursor.getShort();
        if ( bytesLength <= 0 || bytesLength > maxSize )
        {
            return false;
        }
        setBytesLength( bytesLength );
        cursor.getBytes( byteArray, 0, bytesLength );
        return true;
    }

    private boolean readDuration( PageCursor cursor )
    {
        // TODO unify order of fields
        long totalAvgSeconds = cursor.getLong();
        int nanosOfSecond = cursor.getInt();
        long months = cursor.getLong();
        long days = cursor.getLong();
        writeDurationWithTotalAvgSeconds( months, days, totalAvgSeconds, nanosOfSecond );
        return true;
    }

    private boolean readLocalTime( PageCursor cursor )
    {
        writeLocalTime( cursor.getLong() );
        return true;
    }

    private boolean readZonedTime( PageCursor cursor )
    {
        writeTime( cursor.getLong(), cursor.getInt() );
        return true;
    }

    private boolean readDate( PageCursor cursor )
    {
        writeDate( cursor.getLong() );
        return true;
    }

    private boolean readLocalDateTime( PageCursor cursor )
    {
        writeLocalDateTime( cursor.getLong(), cursor.getInt() );
        return true;
    }

    private boolean readZonedDateTime( PageCursor cursor )
    {
        long epochSecondUTC = cursor.getLong();
        int nanoOfSecond = cursor.getInt();
        int encodedZone = cursor.getInt();
        if ( isZoneId( encodedZone ) )
        {
            writeDateTime( epochSecondUTC, nanoOfSecond, asZoneId( encodedZone ) );
        }
        else
        {
            writeDateTime( epochSecondUTC, nanoOfSecond, asZoneOffset( encodedZone ) );
        }
        return true;
    }
    /* </read> */

    /* <write> (write to field state from Value or cursor) */
    @Override
    protected void writeDate( long epochDay ) throws RuntimeException
    {
        if ( !isArray )
        {
            type = Type.DATE;
            long0 = epochDay;
        }
        else
        {
            long0Array[currentArrayOffset] = epochDay;
            currentArrayOffset++;
        }
    }

    @Override
    protected void writeLocalTime( long nanoOfDay ) throws RuntimeException
    {
        if ( !isArray )
        {
            type = Type.LOCAL_TIME;
            long0 = nanoOfDay;
        }
        else
        {
            long0Array[currentArrayOffset] = nanoOfDay;
            currentArrayOffset++;
        }
    }

    @Override
    protected void writeTime( long nanosOfDayUTC, int offsetSeconds ) throws RuntimeException
    {
        if ( !isArray )
        {
            type = Type.ZONED_TIME;
            long0 = nanosOfDayUTC;
            long1 = offsetSeconds;
        }
        else
        {
            long0Array[currentArrayOffset] = nanosOfDayUTC;
            long1Array[currentArrayOffset] = offsetSeconds;
            currentArrayOffset++;
        }
    }

    @Override
    protected void writeLocalDateTime( long epochSecond, int nano ) throws RuntimeException
    {
        if ( !isArray )
        {
            type = Type.LOCAL_DATE_TIME;
            long0 = nano;
            long1 = epochSecond;
        }
        else
        {
            long0Array[currentArrayOffset] = nano;
            long1Array[currentArrayOffset] = epochSecond;
            currentArrayOffset++;
        }
    }

    @Override
    protected void writeDateTime( long epochSecondUTC, int nano, int offsetSeconds ) throws RuntimeException
    {
        writeDateTime( epochSecondUTC, nano, (short) -1, offsetSeconds );
    }

    @Override
    protected void writeDateTime( long epochSecondUTC, int nano, String zoneId )
    {
        writeDateTime( epochSecondUTC, nano, TimeZones.map( zoneId ) );
    }

    protected void writeDateTime( long epochSecondUTC, int nano, short zoneId ) throws RuntimeException
    {
        writeDateTime( epochSecondUTC, nano, zoneId, 0 );
    }

    private void writeDateTime( long epochSecondUTC, int nano, short zoneId, int offsetSeconds )
    {
        if ( !isArray )
        {
            type = Type.ZONED_DATE_TIME;
            long0 = epochSecondUTC;
            long1 = nano;
            long2 = zoneId;
            long3 = offsetSeconds;
        }
        else
        {
            long0Array[currentArrayOffset] = epochSecondUTC;
            long1Array[currentArrayOffset] = nano;
            long2Array[currentArrayOffset] = zoneId;
            long3Array[currentArrayOffset] = offsetSeconds;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeBoolean( boolean value ) throws RuntimeException
    {
        if ( !isArray )
        {
            type = Type.BOOLEAN;
            long0 = value ? TRUE : FALSE;
        }
        else
        {
            long0Array[currentArrayOffset] = value ? TRUE : FALSE;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeInteger( byte value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = value;
            long1 = RawBits.BYTE;
        }
        else
        {
            long0Array[currentArrayOffset] = value;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeInteger( short value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = value;
            long1 = RawBits.SHORT;
        }
        else
        {
            long0Array[currentArrayOffset] = value;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeInteger( int value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = value;
            long1 = RawBits.INT;
        }
        else
        {
            long0Array[currentArrayOffset] = value;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeInteger( long value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = value;
            long1 = RawBits.LONG;
        }
        else
        {
            long0Array[currentArrayOffset] = value;
            currentArrayOffset++;
        }
    }

    @Override
    public void writeFloatingPoint( float value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = Float.floatToIntBits( value );
            long1 = RawBits.FLOAT;
        }
        else
        {
            long0Array[currentArrayOffset] = Float.floatToIntBits( value );
            currentArrayOffset++;
        }
    }

    @Override
    public void writeFloatingPoint( double value )
    {
        if ( !isArray )
        {
            type = Type.NUMBER;
            long0 = Double.doubleToLongBits( value );
            long1 = RawBits.DOUBLE;
        }
        else
        {
            long0Array[currentArrayOffset] = Double.doubleToLongBits( value );
            currentArrayOffset++;
        }
    }

    @Override
    public void writeString( String value ) throws RuntimeException
    {
        byte[] encoded = UTF8.encode( value );
        if ( !isArray )
        {
            type = Type.TEXT;
            byteArray = encoded;
            long0 = encoded.length;
        }
        else
        {
            byteArrayArray[currentArrayOffset] = encoded;
            long0Array[currentArrayOffset] = encoded.length;
            currentArrayOffset++;
        }
        long1 = FALSE;
    }

    @Override
    public void writeString( char value ) throws RuntimeException
    {
        writeString( String.valueOf( value ) );
    }

    @Override
    public void writeDuration( long months, long days, long seconds, int nanos )
    {
        long totalAvgSeconds = months * AVG_MONTH_SECONDS + days * AVG_DAY_SECONDS + seconds;
        writeDurationWithTotalAvgSeconds( months, days, totalAvgSeconds, nanos );
    }

    private void writeDurationWithTotalAvgSeconds( long months, long days, long totalAvgSeconds, int nanos )
    {
        if ( !isArray )
        {
            type = Type.DURATION;
            long0 = totalAvgSeconds;
            long1 = nanos;
            long2 = months;
            long3 = days;
        }
        else
        {
            long0Array[currentArrayOffset] = totalAvgSeconds;
            long1Array[currentArrayOffset] = nanos;
            long2Array[currentArrayOffset] = months;
            long3Array[currentArrayOffset] = days;
            currentArrayOffset++;
        }
    }

    /* <write.array> */

    // Write byte array is a special case,
    // instead of calling beginArray and writing the bytes one-by-one
    // writeByteArray is called so that the bytes can be written in batches.
    // We don't care about that though so just delegate.
    @Override
    public void writeByteArray( byte[] value ) throws RuntimeException
    {
        PrimitiveArrayWriting.writeTo( this, value );
    }

    @Override
    public void beginArray( int size, ArrayType arrayType ) throws RuntimeException
    {
        initializeTypeFromArrayType( arrayType );
        isArray = true;
        arrayLength = size;
        currentArrayOffset = 0;
        switch ( type )
        {
        case ZONED_DATE_TIME_ARRAY:
            initializeZonedDateTimeArray( size );
            break;
        case LOCAL_DATE_TIME_ARRAY:
            initializeLocalDateTimeArray( size );
            break;
        case DATE_ARRAY:
            initializeDateArray( size );
            break;
        case ZONED_TIME_ARRAY:
            initializeZonedTimeArray( size );
            break;
        case LOCAL_TIME_ARRAY:
            initializeLocalTimeArray( size );
            break;
        case DURATION_ARRAY:
            initializeDurationArray( size );
            break;
        case TEXT_ARRAY:
            initializeTextArray( size );
            break;
        case BOOLEAN_ARRAY:
            initializeBooleanArray( size );
            break;
        case NUMBER_ARRAY:
            initializeNumberArray( size );
            break;
        default:
        }
    }

    @Override
    public void endArray() throws RuntimeException
    {   // no-op
    }

    private void initializeTypeFromArrayType( ArrayType arrayType )
    {
        switch ( arrayType )
        {
        case BYTE:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.BYTE;
            break;
        case SHORT:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.SHORT;
            break;
        case INT:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.INT;
            break;
        case LONG:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.LONG;
            break;
        case FLOAT:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.FLOAT;
            break;
        case DOUBLE:
            type = Type.NUMBER_ARRAY;
            long1 = RawBits.DOUBLE;
            break;
        case BOOLEAN:
            type = Type.BOOLEAN_ARRAY;
            break;
        case STRING:
            type = Type.TEXT_ARRAY;
            break;
        case CHAR:
            type = Type.TEXT_ARRAY;
            break;
        case POINT:
            throw new UnsupportedOperationException( "Not implemented yet" );
        case ZONED_DATE_TIME:
            type = Type.ZONED_DATE_TIME_ARRAY;
            break;
        case LOCAL_DATE_TIME:
            type = Type.LOCAL_DATE_TIME_ARRAY;
            break;
        case DATE:
            type = Type.DATE_ARRAY;
            break;
        case ZONED_TIME:
            type = Type.ZONED_TIME_ARRAY;
            break;
        case LOCAL_TIME:
            type = Type.LOCAL_TIME_ARRAY;
            break;
        case DURATION:
            type = Type.DURATION_ARRAY;
            break;
        default:
            throw new IllegalArgumentException( "Unknown array type " + arrayType );
        }
    }

    private void initializeNumberArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        // plain long1 for number type
    }

    private void initializeBooleanArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
    }

    private void initializeTextArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        byteArrayArray = ensureBigEnough( byteArrayArray, size );
        // long1 (bytesDereferenced) - Not needed because we never leak bytes from string array
        // long2 (ignoreLength) - Not needed because kept on 'global' level for full array
        // long3 (isHighest) - Not needed because kept on 'global' level for full array
    }

    private void initializeDurationArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        long1Array = ensureBigEnough( long1Array, size );
        long2Array = ensureBigEnough( long2Array, size );
        long3Array = ensureBigEnough( long3Array, size );
    }

    private void initializeLocalTimeArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
    }

    private void initializeZonedTimeArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        long1Array = ensureBigEnough( long1Array, size );
    }

    private void initializeDateArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
    }

    private void initializeLocalDateTimeArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        long1Array = ensureBigEnough( long1Array, size );
    }

    private void initializeZonedDateTimeArray( int size )
    {
        long0Array = ensureBigEnough( long0Array, size );
        long1Array = ensureBigEnough( long1Array, size );
        long2Array = ensureBigEnough( long2Array, size );
        long3Array = ensureBigEnough( long3Array, size );
    }
    /* </write.array> */
    /* </write> */

    /* <copyFrom.helpers> */
    private void copyNumberArrayFrom( GenericKeyState key )
    {
        initializeNumberArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
    }

    private void copyBooleanArrayFrom( GenericKeyState key )
    {
        initializeBooleanArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
    }

    private void copyTextArrayFrom( GenericKeyState key )
    {
        initializeTextArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
        this.long1 = FALSE;
        this.long2 = key.long2;
        this.long3 = key.long3;
        for ( int i = 0; i < key.arrayLength; i++ )
        {
            short targetLength = (short) key.long0Array[i];
            this.byteArrayArray[i] = ensureBigEnough( this.byteArrayArray[i], targetLength );
            System.arraycopy( key.byteArrayArray[i], 0, this.byteArrayArray[i], 0, targetLength );
        }
    }

    private void copyDurationArrayFrom( GenericKeyState key )
    {
        initializeDurationArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
        System.arraycopy( key.long1Array, 0, this.long1Array, 0, key.arrayLength );
        System.arraycopy( key.long2Array, 0, this.long2Array, 0, key.arrayLength );
        System.arraycopy( key.long3Array, 0, this.long3Array, 0, key.arrayLength );
    }

    private void copyLocalTimeArrayFrom( GenericKeyState key )
    {
        initializeLocalTimeArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
    }

    private void copyZonedTimeArrayFrom( GenericKeyState key )
    {
        initializeZonedTimeArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
        System.arraycopy( key.long1Array, 0, this.long1Array, 0, key.arrayLength );
    }

    private void copyDateArrayFrom( GenericKeyState key )
    {
        initializeDateArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
    }

    private void copyLocalDateTimeArrayFrom( GenericKeyState key )
    {
        initializeLocalDateTimeArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
        System.arraycopy( key.long1Array, 0, this.long1Array, 0, key.arrayLength );
    }

    private void copyZonedDateTimeArrayFrom( GenericKeyState key )
    {
        initializeZonedDateTimeArray( key.arrayLength );
        System.arraycopy( key.long0Array, 0, this.long0Array, 0, key.arrayLength );
        System.arraycopy( key.long1Array, 0, this.long1Array, 0, key.arrayLength );
        System.arraycopy( key.long2Array, 0, this.long2Array, 0, key.arrayLength );
        System.arraycopy( key.long3Array, 0, this.long3Array, 0, key.arrayLength );
    }
    /* </copyFrom.helpers> */

    /* <helpers> */
    private void setBytesLength( int length )
    {
        if ( booleanOf( long1 ) || byteArray == null || byteArray.length < length )
        {
            long1 = FALSE;

            // allocate a bit more than required so that there's a higher chance that this byte[] instance
            // can be used for more keys than just this one
            byteArray = new byte[length + length / 2];
        }
        long0 = length;
    }

    private static byte[] ensureBigEnough( byte[] array, int targetLength )
    {
        return array == null || array.length < targetLength ? new byte[targetLength] : array;
    }

    private static byte[][] ensureBigEnough( byte[][] array, int targetLength )
    {
        return array == null || array.length < targetLength ? new byte[targetLength][] : array;
    }

    private static long[] ensureBigEnough( long[] array, int targetLength )
    {
        return array == null || array.length < targetLength ? new long[targetLength] : array;
    }

    private static NumberValue numberAsValue( long long0, long long1 )
    {
        // There's a difference between composing a single text value and a array text value
        // and there's therefore no common "raw" variant of it
        return RawBits.asNumberValue( long0, (byte) long1 );
    }

    private static BooleanValue booleanAsValue( long long0 )
    {
        return Values.booleanValue( booleanAsValueRaw( long0 ) );
    }

    private static boolean booleanAsValueRaw( long long0 )
    {
        return booleanOf( long0 );
    }

    private static Value textAsValue( byte[] byteArray, long long0 )
    {
        // There's a difference between composing a single text value and a array text value
        // and there's therefore no common "raw" variant of it
        return byteArray == null ? NO_VALUE : Values.utf8Value( byteArray, 0, (int) long0 );
    }

    private static String textAsValueRaw( byte[] byteArray, long long0 )
    {
        return byteArray == null ? null : UTF8.decode( byteArray, 0, (int) long0 );
    }

    private static DurationValue durationAsValue( long long0, long long1, long long2, long long3  )
    {
        // DurationValue has no "raw" variant
        long seconds = long0 - long2 * AVG_MONTH_SECONDS - long3 * AVG_DAY_SECONDS;
        return DurationValue.duration( long2, long3, seconds, long1 );
    }

    private static LocalTimeValue localTimeAsValue( long long0 )
    {
        return LocalTimeValue.localTime( localTimeAsValueRaw( long0 ) );
    }

    private static LocalTime localTimeAsValueRaw( long long0 )
    {
        return LocalTimeValue.localTimeRaw( long0 );
    }

    private static Value zonedTimeAsValue( long long0, long long1 )
    {
        OffsetTime time = zonedTimeAsValueRaw( long0, long1 );
        return time != null ? TimeValue.time( time ) : NO_VALUE;
    }

    private static OffsetTime zonedTimeAsValueRaw( long long0, long long1 )
    {
        if ( TimeZones.validZoneOffset( (int) long1 ) )
        {
            return TimeValue.timeRaw( long0, ZoneOffset.ofTotalSeconds( (int) long1 ) );
        }
        // TODO Getting here means that after a proper read this value is plain wrong... shouldn't something be thrown instead? Yes and same for TimeZones
        return null;
    }

    private static DateValue dateAsValue( long long0 )
    {
        return DateValue.date( dateAsValueRaw( long0 ) );
    }

    private static LocalDate dateAsValueRaw( long long0 )
    {
        return DateValue.epochDateRaw( long0 );
    }

    private static LocalDateTimeValue localDateTimeAsValue( long long0, long long1 )
    {
        return LocalDateTimeValue.localDateTime( localDateTimeAsValueRaw( long0, long1 ) );
    }

    private static LocalDateTime localDateTimeAsValueRaw( long long0, long long1 )
    {
        return LocalDateTimeValue.localDateTimeRaw( long1, long0 );
    }

    private static DateTimeValue zonedDateTimeAsValue( long long0, long long1, long long2, long long3 )
    {
        return DateTimeValue.datetime( zonedDateTimeAsValueRaw( long0, long1, long2, long3 ) );
    }

    private static ZonedDateTime zonedDateTimeAsValueRaw( long long0, long long1, long long2, long long3 )
    {
        return TimeZones.validZoneId( (short) long2 ) ?
               DateTimeValue.datetimeRaw( long0, long1, ZoneId.of( TimeZones.map( (short) long2 ) ) ) :
               DateTimeValue.datetimeRaw( long0, long1, ZoneOffset.ofTotalSeconds( (int) long3 ) );
    }

    private static boolean isHighestText( long long3 )
    {
        return long3 == TRUE;
    }

    private static boolean booleanOf( long longValue )
    {
        return longValue == TRUE;
    }

    @Override
    public String toString()
    {
        return String.format( "GenericKeyState[%s]", asValue().toString() );
    }

    @FunctionalInterface
    interface ArrayElementComparator
    {
        int compare( GenericKeyState o1, GenericKeyState o2, int i );
    }

    @FunctionalInterface
    interface ArrayElementReader
    {
        boolean readFrom( PageCursor cursor );
    }

    @FunctionalInterface
    interface ArrayElementValueFactory<T>
    {
        T from( int i );
    }
    /* </helpers> */
}
