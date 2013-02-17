/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.common.serialization.types;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.orientechnologies.common.directmemory.ODirectMemory;

/**
 * Serializer for {@link BigDecimal} type.
 * 
 * @author Andrey Lomakin
 * @since 03.04.12
 */
public class ODecimalSerializer implements OBinarySerializer<BigDecimal> {
  public static final ODecimalSerializer INSTANCE = new ODecimalSerializer();
  public static final byte               ID       = 18;

  public int getObjectSize(BigDecimal object) {
    return OIntegerSerializer.INT_SIZE + OBinaryTypeSerializer.INSTANCE.getObjectSize(object.unscaledValue().toByteArray());
  }

  public int getObjectSize(byte[] stream, int startPosition) {
    final int size = OIntegerSerializer.INT_SIZE
        + OBinaryTypeSerializer.INSTANCE.getObjectSize(stream, startPosition + OIntegerSerializer.INT_SIZE);
    return size;
  }

  public void serialize(BigDecimal object, byte[] stream, int startPosition) {
    OIntegerSerializer.INSTANCE.serialize(object.scale(), stream, startPosition);
    startPosition += OIntegerSerializer.INT_SIZE;
    OBinaryTypeSerializer.INSTANCE.serialize(object.unscaledValue().toByteArray(), stream, startPosition);

  }

  public BigDecimal deserialize(byte[] stream, int startPosition) {
    final int scale = OIntegerSerializer.INSTANCE.deserialize(stream, startPosition);
    startPosition += OIntegerSerializer.INT_SIZE;

    final byte[] unscaledValue = OBinaryTypeSerializer.INSTANCE.deserialize(stream, startPosition);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(byte[] stream, int startPosition) {
    final int size = OIntegerSerializer.INT_SIZE
        + OBinaryTypeSerializer.INSTANCE.getObjectSizeNative(stream, startPosition + OIntegerSerializer.INT_SIZE);
    return size;
  }

  public void serializeNative(BigDecimal object, byte[] stream, int startPosition) {
    OIntegerSerializer.INSTANCE.serializeNative(object.scale(), stream, startPosition);
    startPosition += OIntegerSerializer.INT_SIZE;
    OBinaryTypeSerializer.INSTANCE.serializeNative(object.unscaledValue().toByteArray(), stream, startPosition);
  }

  public BigDecimal deserializeNative(byte[] stream, int startPosition) {
    final int scale = OIntegerSerializer.INSTANCE.deserializeNative(stream, startPosition);
    startPosition += OIntegerSerializer.INT_SIZE;

    final byte[] unscaledValue = OBinaryTypeSerializer.INSTANCE.deserializeNative(stream, startPosition);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  @Override
  public void serializeInDirectMemory(BigDecimal object, ODirectMemory memory, long pointer) {
    OIntegerSerializer.INSTANCE.serializeInDirectMemory(object.scale(), memory, pointer);
    pointer += OIntegerSerializer.INT_SIZE;
    OBinaryTypeSerializer.INSTANCE.serializeInDirectMemory(object.unscaledValue().toByteArray(), memory, pointer);
  }

  @Override
  public BigDecimal deserializeFromDirectMemory(ODirectMemory memory, long pointer) {
    final int scale = memory.getInt(pointer);
    pointer += OIntegerSerializer.INT_SIZE;

    final byte[] unscaledValue = OBinaryTypeSerializer.INSTANCE.deserializeFromDirectMemory(memory, pointer);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  @Override
  public int getObjectSizeInDirectMemory(ODirectMemory memory, long pointer) {
    final int size = OIntegerSerializer.INT_SIZE
        + OBinaryTypeSerializer.INSTANCE.getObjectSizeInDirectMemory(memory, pointer + OIntegerSerializer.INT_SIZE);
    return size;
  }

  public boolean isFixedLength() {
    return false;
  }

  public int getFixedLength() {
    return 0;
  }
}
