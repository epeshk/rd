#ifndef RD_CPP_RDTASKIMPL_H
#define RD_CPP_RDTASKIMPL_H

#include "Polymorphic.h"
#include "RdTaskResult.h"

#include "thirdparty.hpp"


namespace rd {
	template<typename, typename>
	class RdTask;

	namespace detail {
		template<typename T, typename S = Polymorphic<T>>
		class RdTaskImpl {
		private:
			mutable Property<RdTaskResult<T, S>> result;
		public:
			template<typename, typename>
			friend
			class ::rd::RdTask;
		};
	}
}


#endif //RD_CPP_RDTASKIMPL_H
