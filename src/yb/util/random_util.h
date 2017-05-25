// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#ifndef YB_UTIL_RANDOM_UTIL_H
#define YB_UTIL_RANDOM_UTIL_H

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <random>
#include <string>

namespace yb {

class Random;

// Writes exactly n random bytes to dest using the parameter Random generator.
// Note RandomString() does not null-terminate its strings, though '\0' could
// be written to dest with the same probability as any other byte.
void RandomString(void* dest, size_t n, Random* rng);

// Generate a 32-bit random seed from several sources, including timestamp,
// pid & tid.
uint32_t GetRandomSeed32();

std::string RandomHumanReadableString(int len, Random* rnd);

class RandomDeviceSequence {
 public:
  template<class It>
  void generate(It begin, const It& end) {
    std::generate(begin, end, [this] { return device_(); });
  }
 private:
  std::random_device device_;
};

// Correct seeding of random number generator.
// It is quite futile to use 32bit seed for generator with 19968bit state, like mt19937.
template<class Engine>
void Seed(Engine* engine) {
  RandomDeviceSequence sequence;
  engine->seed(sequence);
}

} // namespace yb

#endif // YB_UTIL_RANDOM_UTIL_H
